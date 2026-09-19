# Vodka

> **Experimental.** Vodka is a research project, not a supported product. It does not ship
> any Roblox code or binaries.

Vodka is an experimental runtime that aims to launch the **real, unmodified Roblox Studio
Windows executable** (`RobloxStudioBeta.exe`) on **Android devices with ARM64 CPUs**, by
composing a user-space compatibility stack:

```
Android (ARM64) → Linux container → FEX-Emu → Wine (x86-64) → DXVK → Roblox Studio
```

It is the mirror image of projects like Sober/Vinegar (which run the Roblox *client* on
desktop Linux): Vodka runs the *creator tool* on *mobile ARM*.

## Status

**Pre-alpha / design phase.** Nothing runs yet. The current deliverable is the architecture
and plan in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

| Milestone | Description | State |
|-----------|-------------|-------|
| M0 | Architecture, repo layout, build plan | ✅ done |
| M1 | ARM64 glibc container boots on device | 🚧 in progress |
| M2 | FEX-Emu runs an x86-64 Linux binary | 🚧 implemented, needs device |
| M3 | Wine x86-64 under FEX, window on Android Surface | 🚧 scripted, needs device |
| M4 | DXVK + Vulkan thunks, D3D11 at playable FPS | ⬜ planned |
| M5 | Studio installer + launch to login screen | ⬜ planned |
| M6 | Input/audio/storage; edit and playtest a place | ⬜ planned |

### M1 progress

- `native/container` — portable C++17 container launcher with two backends: fast
  user/mount namespaces (with `uid_map`/`gid_map` setup and `chroot`) and a universal
  `proot` fallback. Capability probing and backend selection included.
- Host test suite (`native/tests`) passes; it launches a process through both backends and
  runs a command inside a real user+mount namespace.
- Android app scaffold (`app/`) with a Kotlin control plane, JNI bridge, and a
  **Material 3** UI (status card, backend selector, progress, per-payload install actions).
- `native/archive` — dependency-free tar + gzip extractor (zlib, shipped by the NDK) with
  path-traversal protection; used to install rootfs payloads on-device. Validated against a
  real Ubuntu 24.04 arm64 base rootfs (2562 files, 100 MB, symlinks and modes preserved);
  GNU sparse archives are rejected explicitly.
- `runtime/rootfs-arm64/` — reproducible ARM64 Debian rootfs build (`Dockerfile` +
  `build.sh`) producing `rootfs-arm64.tar.gz`, with a Wine/DXVK prefix setup helper.
- In-app install flow: pick each archive with the system file picker, extract into
  `files/runtime/…`, then launch through the container.
- `tools/device-probe/probe.sh` reports kernel, namespace, SoC, and Vulkan capabilities.
- Debug APK builds and packages `arm64-v8a/libvodka_jni.so` (NDK r26b, minSdk 29) with ten
  JNI entrypoints (container, archive, runtime).

Still pending for M1: running the installed rootfs end-to-end on a physical device.

### M2 progress

- `native/runtime` — launch-plan and FEX-config generator (argv/env/cwd + JSON, with
  escaping), exposed to Kotlin via `NativeRuntime.*`; unit-tested on the host.
- `runtime/fex/` — cross-compiles FEX for aarch64 (`build.sh`) and ships FEX config
  templates. Uses a **directory rootfs** and explicit `FEX wine` invocation (no root, no
  `binfmt_misc`).
- `runtime/rootfs-x86_64/` — amd64 Debian Wine rootfs (`Dockerfile` + `build.sh`) with
  `wine64`, `winetricks`, and staged DXVK.
- `runtime/rootfs-arm64/overlay/usr/local/bin/vodka-wine-setup` — creates the Wine prefix
  under FEX and stages DXVK DLLs.
- App flow: **base rootfs → Wine rootfs → FEX → Set up Wine prefix → Launch Roblox Studio**.

Design and the upstream FEX facts behind these choices: [`docs/M2-FEX-WINE.md`](docs/M2-FEX-WINE.md).

## Building

Native library and tests (any Linux host):

```sh
cmake -S native -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel
ctest --test-dir build/native --output-on-failure
```

Android APK (requires JDK 17, Android SDK, NDK 26+):

```sh
gradle :app:assembleDebug
```

Rootfs and emulator payloads (container engine with `buildx`, `gzip`/`pigz`, and an
aarch64 cross toolchain for FEX):

```sh
./runtime/rootfs-arm64/build.sh      # -> rootfs-arm64.tar.gz
./runtime/rootfs-x86_64/build.sh     # -> rootfs-x86_64.tar.gz
./runtime/fex/build.sh               # -> fex-arm64.tar.gz
```

Install them in the app in that order, run **Set up Wine prefix**, then **Launch Roblox
Studio**. See [`docs/M2-FEX-WINE.md`](docs/M2-FEX-WINE.md).

CI runs the native and Android jobs on every push/PR; the rootfs job runs on manual
`workflow_dispatch`. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## Why this is hard

Mobile GPUs, Android's Bionic libc, scoped storage, and the lack of a display server make
this far harder than Wine on a Linux desktop. The three biggest problems are
**GPU acceleration** (D3D11 → DXVK → Vulkan → mobile driver), **presentation** (no X11/Wayland
on Android), and **process/container isolation** (no root). See the architecture doc for how
each is addressed.

## Legal

Vodka is licensed **GPL-3.0** (see [`LICENSE`](LICENSE)). It contains no Roblox code,
assets, or binaries. Any Roblox Studio payload is downloaded by the user from Roblox's own
servers at runtime, subject to Roblox's Terms of Use. Do not redistribute Roblox software
with Vodka. Roblox and Roblox Studio are trademarks of Roblox Corporation; Vodka is not
affiliated with or endorsed by Roblox Corporation.

## Layout

| Path | Contents |
|------|----------|
| `docs/` | architecture, the FEX/Wine layer, decisions |
| `app/` | Android application (Kotlin, Material 3) |
| `app/src/main/cpp/` | Android-native JNI bridges, Bionic (C/C++) |
| `native/container/` | namespace/`proot` container launcher |
| `native/archive/` | tar + gzip extractor for rootfs payloads |
| `native/runtime/` | FEX/Wine launch plan and config generator |
| `runtime/rootfs-arm64/` | ARM64 glibc rootfs (build + Wine setup helper) |
| `runtime/rootfs-x86_64/` | amd64 Wine rootfs (guest) and DXVK staging |
| `runtime/fex/` | FEX aarch64 build + config templates |
| `tools/` | Studio fetcher, rootfs packer, device probes |

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/M2-FEX-WINE.md`](docs/M2-FEX-WINE.md).
