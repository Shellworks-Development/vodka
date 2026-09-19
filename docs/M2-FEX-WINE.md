# M2 — FEX + Wine runtime layer

How Vodka turns an installed rootfs pair plus FEX into a launched Roblox Studio, and the
facts about upstream FEX that constrain the design. Status: **implemented, not yet run on an
ARM64 device** (see §7).

## 1. Launch chain

```
/usr/bin/FEX  /usr/bin/wine  <RobloxStudioBeta.exe>  [args]
   ▲              ▲                ▲
   │              │                └ guest x86-64 PE, mapped by Wine (not execve'd)
   │              └ guest x86-64 ELF, resolved inside the directory rootfs
   └ ARM64 binary in the container; it emulates the guest and handles guest execve itself
```

`FEX` is invoked explicitly (not via `binfmt_misc`, which needs root). FEX emulates the
guest kernel's `execve`, so Wine's internal execs of other x86-64 binaries stay inside FEX.

## 2. Rootfs: directory, not image

Upstream FEX's `RootFS` config option accepts **either a filesystem path or the name of an
image** under the FEX data directory. Official images are EroFS/SquashFS and are mounted at
runtime with `squashfuse`/`erofsfuse` (FUSE). On non-root Android we avoid mounting and use
a **plain directory** rootfs instead:

```
$XDG_DATA_HOME/fex-emu/RootFS/<name>/     # or any absolute path (what Vodka uses)
```

Vodka keeps the ARM64 rootfs writable and puts the x86-64 rootfs inside the container,
bind-mounted read-mostly at `/opt/vodka/rootfs-x86_64`. All mutable state (the Wine prefix,
FEX config) lives in the container home, not in the guest image.

## 3. Config schema (verified against FEX `Source/Common/Config.cpp`)

FEX reads a JSON config whose shape is:

```json
{
  "Config": { "Key": "Value", "AnotherKey": "1" },
  "ThunksDB": { "GL": 0 }
}
```

Values are strings, booleans as `"1"`/`"0"`. Locations:

- Global config: `$XDG_CONFIG_HOME/fex-emu/Config.json` (else `~/.fex-emu/Config.json`,
  else `~/.config/fex-emu/Config.json`).
- Data dir: `$XDG_DATA_HOME/fex-emu/` (else `~/.fex-emu/`, `~/.local/share/fex-emu/`).
- Per-application override: `FEX_APP_CONFIG` env var → a JSON file of the same shape.

Vodka sets `XDG_CONFIG_HOME`/`XDG_DATA_HOME` to fixed container paths so FEX never depends
on Android's `$HOME`. `native/runtime` generates both files:

| Key | Value | Why |
|-----|-------|-----|
| `RootFS` | `/opt/vodka/rootfs-x86_64` | directory guest rootfs |
| `ThunkHostLibs` | `/usr/lib/fex-emu/HostThunks` | ARM64 thunk libraries from the FEX build |
| `TSOEnabled` | `1` | x86 TSO memory model; disabling breaks multithreaded apps |
| `HideHypervisorBit` | `1` | some Windows software misbehaves when the hypervisor CPUID bit is set |

`runtime/fex/config/` holds templates; `native/runtime/src/launch_plan.cpp` emits the
generated JSON with correct escaping.

## 4. Launch plan and environment

`vodka::build_launch_plan` produces `argv`, `env`, and a working directory
(`native/runtime/include/vodka/runtime.h`). The plan:

- **argv**: `{ FEX, wine, studio_exe, ...studio_args }`
- **env**: `HOME`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_CACHE_HOME`, `PATH`,
  `FEX_APP_CONFIG`, `WINEPREFIX`, `WINEDEBUG=-all`, `DISPLAY`,
  `WINEDLLOVERRIDES=d3d11,dxgi,d3d10core,d3d9,d3d8=n,b` (use native DXVK), optional
  `PULSE_SERVER`, plus caller-supplied extras.
- **cwd**: the studio executable's directory.

The same code path is exposed to Kotlin over JNI (`NativeRuntime.planArgs/planEnv/...`) and
covered by `native/tests/runtime_test.cpp`.

## 5. Wine, DXVK, and prefix setup

The x86-64 rootfs (`runtime/rootfs-x86_64/`) is a Debian bookworm amd64 image with
`wine`/`wine64`, `winetricks`, and a staged DXVK under `/opt/vodka/dxvk`.

Prefix creation must happen at runtime, because `wineboot` is itself an x86-64 program that
must run under FEX. `runtime/rootfs-arm64/overlay/usr/local/bin/vodka-wine-setup` does:

1. `FEX /usr/bin/wine wineboot -u` — create the prefix (once).
2. `FEX /usr/bin/wine wineserver -w` — flush.
3. Copy DXVK `*.dll` from the guest rootfs into `system32` / `syswow64`.

The app calls it by launching `/bin/sh /usr/local/bin/vodka-wine-setup` inside the container
with the launch env. The `WINEDLLOVERRIDES` from the launch plan make Wine prefer those
native DXVK DLLs over its built-ins.

## 6. Building the layer

```sh
# FEX for aarch64 (cross-compiled; produces bin/FEX + lib/fex-emu/HostThunks)
./runtime/fex/build.sh

# x86-64 Wine rootfs (amd64; wine + winetricks + DXVK)
./runtime/rootfs-x86_64/build.sh

# base ARM64 rootfs (glibc, X11, proot, ...)
./runtime/rootfs-arm64/build.sh
```

Install order in the app: **base rootfs → Wine rootfs → FEX → Set up Wine prefix → Launch**.

## 7. What is verified, and what is not

Verified on an x86_64 host:

- `native/runtime` unit tests (launch plan, env, JSON escaping) pass.
- APK builds and ships all JNI entrypoints, including `NativeRuntime.*`.
- The archive extractor handles a real Ubuntu amd64/arm64 rootfs.

Not yet verified (needs an ARM64 Android device, or an ARM64 host):

- FEX starts and runs an x86-64 binary on Android's kernel.
- Wine boot under FEX; DXVK under FEX + mobile Vulkan.
- Studio launch, GPU feature level, input, audio.

These are tracked as M2–M4 exit criteria. The first on-device milestone is
`FEX /bin/echo hello` inside the container, then `wine notepad`, then Studio.
