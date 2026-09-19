# Vodka — Architecture

Status: **design**, pre-M0. This document is the reference for how Vodka is meant to work,
why each choice was made, and what remains unproven.

## 1. Objective and non-goals

**Objective.** Launch a genuine x86-64 Windows build of Roblox Studio on a consumer
Android ARM64 device, with usable (not native) performance, GPU acceleration, mouse and
keyboard input, audio, and persistent project storage — without root and without shipping
Roblox binaries.

**Non-goals (for now).**

- Reimplementing Roblox or Luau. Vodka runs the real PE binary via compatibility, not a clone.
- The Roblox *client* / playing games. Studio is the target; the client has anti-tamper
  (`Hyperion`/`Byfron`) that makes this approach non-viable.
- x86 (32-bit) Studio. Modern Studio is x86-64; the 32-bit path is out of scope.
- Desktop Linux/macOS hosts. Vodka is Android-first, though the runtime layers are portable.

## 2. The execution stack

Vodka is a stack of translation layers. Read bottom-up; each layer only understands the
layer above it through a well-defined interface.

```
┌──────────────────────────────────────────────────────────────────────┐
│ L3  RobloxStudioBeta.exe  + Windows DLLs (x86-64 PE)                  │
├──────────────────────────────────────────────────────────────────────┤
│     Wine (x86-64)                DXVK / vkd3d (x86-64)                │
│     Win32 API → POSIX            D3D9/11/12 → Vulkan                  │
├──────────────────────────────────────────────────────────────────────┤
│ L2  FEX-Emu (ARM64 Linux ELF)                                         │
│     x86-64 instruction translation  +  library/syscall thunking       │
├──────────────────────────────────────────────────────────────────────┤
│ L1  ARM64 glibc userland in a container                               │
│     glibc, X11 server, PipeWire/Pulse, Mesa/Turnip/PanVK, fonts       │
├──────────────────────────────────────────────────────────────────────┤
│ L0  Android app (Kotlin) + native bridges (C/C++, Bionic)             │
│     Surface, input, audio, storage, lifecycle, container management   │
├──────────────────────────────────────────────────────────────────────┤
│     Android ARM64 kernel  •  mobile Vulkan driver  •  display panel   │
└──────────────────────────────────────────────────────────────────────┘
```

### Why x86-64 Wine under FEX, and not ARM64 Wine?

Wine's job is to interpret PE/Windows ABI. The Windows image we must run is x86-64. An
ARM64-native Wine would still need an instruction translator to execute x86-64 PE code and
would have to marshal the entire Windows ABI across two architectures inside the same
process. The proven path (Asahi Linux, FEX's own Proton work) is to run the **whole x86-64
Linux userland — Wine included — under FEX**, and only *thunk* the expensive, architecture-
agnostic interfaces (Vulkan, OpenGL, audio, X11) to native ARM64. This keeps one consistent
ABI boundary at the FEX thunk layer instead of two.

### Why the container exists

Android's libc is Bionic, not glibc. FEX, Wine, Mesa, and X11 all expect glibc semantics
(`dlopen`/`ld.so`, POSIX locale, `fork`-heavy code, `/usr` layout). Rather than port each to
Bionic, L1 provides a real glibc ARM64 userland. FEX additionally requires an **x86-64
RootFS** (its own rootfs) to supply x86-64 glibc and the Wine loader tree. So there are
actually two root filesystems:

- **ARM64 rootfs** — glibc, `fex`, X server, audio server, GPU drivers (L1).
- **x86-64 rootfs** — glibc, `wine`, DXVK, Windows runtime DLLs, Studio (L2/L3).

FEX sits between them: it is an ARM64 binary that loads the x86-64 rootfs as its guest.

## 3. Component responsibilities

### L0 — Android application (`app/`)

Kotlin, targeting `minSdk` TBD (Android 10+ likely, for Vulkan 1.1 and storage APIs).

- UI: library of places/projects, settings, device-profile detection, first-run setup.
- Lifecycle: acquire/refresh the rootfs, download Studio, start/stop the runtime session.
- `SurfaceView` (or `SurfaceControl`) providing the Android `Surface` for presentation.
- Permissions: storage, input devices, background/foreground service.
- Owns **no** emulation logic; it supervises the native daemon.

### L0 — Native bridges (`app/src/main/cpp/` and `native/`)

Bionic C/C++, exposed over JNI and a control socket:

- `container` — creates the mount/user/PID namespace (or falls back to `proot`), binds
  `/data/data/<pkg>/...` and external storage, starts the runtime, reaps it.
- `display` — hosts the X server's framebuffer, composites to the Android `Surface`,
  reports surface resize/DPI, handles VSync.
- `input` — injects Android touch/keyboard/mouse/gamepad into the X server's protocol.
- `audio` — a `PipeWire`/PulseAudio client that renders the guest sink to `AAudio`/`Oboe`.
- `vulkan-host` — the ARM64-side implementation FEX thunks Vulkan calls into (thin loader
  gluing to the device driver or a bundled Turnip/PanVK).

These are the only components that touch Android platform APIs directly.

### L1 — ARM64 glibc container (`runtime/rootfs-arm64/`)

Debian/Ubuntu ARM64 (or a minimal custom image) plus:

- `fex` (ARM64 build) and its x86-64 rootfs.
- A display server. **Chosen: an X server** (Xwayland-capable), because Wine's X11 driver
  and DXVK's X11 WSI are the most battle-tested on Linux. A Wayland-only path is a later
  optimization; Wine's Wayland driver plus a wlroots compositor is the natural successor.
- Audio: PipeWire (preferred) or PulseAudio.
- GPU: device Vulkan via thunking; bundled **Turnip** (freedreno) for Adreno, **PanVK**
  for Mali, with `lavapipe` as a software fallback for bring-up and CI.
- Fonts, `libxkbcommon`, cert store, timezone.

### L2 — FEX-Emu (`runtime/fex/`)

Upstream FEX (MIT), ARM64, with configuration for:

- **Thunking** GPU/audio/X11 so those calls never execute x86-64 code (the single biggest
  performance lever).
- **JIT code cache** to remove first-run stutter across Studio launches.
- **Per-app config** for Roblox (`FEXConfig`) — memory-model tuning, `TSO` toggles, etc.
- Kernel-feature probes: page size, `memfd`, pointer auth, `mmap` limits.

### L3 — Wine + DXVK (`runtime/wine/`, `runtime/dxvk/`)

- Wine x86-64 (from WineHQ or a Proton-derived build) under FEX.
- Winetricks-provided runtimes: `vcrun`, `.NET` (as needed by Studio), `corefonts`.
- DXVK (`dxgi`, `d3d9/10/11`, `d3dcompiler_47`) and `vkd3d-proton` (D3D12) for the renderer.
- `RobloxStudioBeta.exe` and helpers fetched by `tools/fetch-studio`, never committed.

## 4. Frame data path (worked example)

```
Studio calls ID3D11Device::CreateTexture2D
        ↓  (x86-64 code, under FEX)
DXVK translates to VkCreateImage
        ↓  (x86-64 code, under FEX)
FEX thunk: matching ARM64 Vulkan entrypoint is called directly
        ↓  (native ARM64, L1)
libvulkan (device driver / Turnip / PanVK)
        ↓
GPU renders into a swapchain image
        ↓  DXVK presents to X11 window (VkKhrXlibSurface)
X server composites window tree into its framebuffer (L1)
        ↓  shared memory / dmabuf
Android display bridge blits to the Surface (L0)
        ↓
SurfaceFlinger → panel
```

The critical property: only *instruction-level* work is emulated. Shader compilation,
texture upload, and command submission cross into native code at the thunk boundary.

## 5. The three hard problems

### 5.1 Container without root

Android forbids `chroot` and typically restricts unprivileged user namespaces. Two backends:

| Backend | Mechanism | Speed | Availability |
|---------|-----------|-------|--------------|
| `namespaces` | `unshare(CLONE_NEWUSER\|NEWNS\|NEWPID)` + `pivot_root`, bind mounts | native | depends on kernel `CONFIG_USER_NS` and OEM seccomp |
| `proot` | `ptrace` syscall interception, no privileges | 2–10× overhead | universal |

Vodka probes for the `namespaces` backend at first run and falls back to `proot`. Bind
mounts under `namespaces` also let us expose external storage as real POSIX paths; `proot`
emulates that more slowly. The `container` bridge abstracts both behind one interface.

### 5.2 Presentation

Android has no display server. The design uses an **X server inside the container whose
backing store is an Android `Surface`**, in the spirit of existing projects (Termux:X11,
XServer XSDL) but purpose-built:

- The X server writes to a shared buffer; the `display` bridge hands the buffer to the
  Surface (ASurfaceTexture or AHardwareBuffer/dmabuf for zero-copy where possible).
- Wine sees a normal X11 window, so the DXVK X11 WSI works unmodified.
- Touch maps to pointer + a Studio-oriented modifier toolbar; hardware keyboard/mouse and
  gamepads pass through.
- A later milestone swaps to Wine's Wayland driver + a wlroots compositor with an Android
  backend to cut a copy and improve latency.

### 5.3 GPU

Mobile Vulkan drivers vary in features. Studio needs a D3D11 feature level that DXVK maps
onto Vulkan 1.1+; this means **descriptor indexing, timeline semaphores, and sufficient
memory** are the gating features. Mitigations:

- Prefer open drivers where the vendor blob is weak: **Turnip** (Adreno) and **PanVK** (Mali).
- Ship a **device support database** keyed by GPU, with recommended driver, DXVK options,
  and known-broken extensions.
- Provide `lavapipe` (LLVM software Vulkan) for CI and unsupported devices — slow, but proves
  the stack end-to-end without vendor dependencies.

## 6. Build and artifact model

Because Bionic and glibc targets differ, Vodka builds three artifact classes, each with a
distinct toolchain and CI job:

| Layer | Target | Toolchain | Output |
|-------|--------|-----------|--------|
| L0 | Android arm64-v8a | NDK + Gradle/CMake | APK |
| L1 | Linux aarch64, glibc | cross-gcc/clang + Debian image build | `rootfs-arm64.tar.zst` |
| L2/L3 | Linux x86-64, glibc (+ ARM64 FEX) | containerized x86-64 build, FEX for ARM64 | `rootfs-x86_64.tar.zst`, `fex` |

CI uses GitHub Actions: Android job (gradle + NDK), FEX job (CMake, ARM64), Wine job
(build in an x86-64 container, package), rootfs job (assemble and pack). Rootfs images are
published as release assets; the APK can fetch them on first run or they can be side-loaded.

## 7. Repository layout (target)

```
vodka/
├── app/                          # Android app (Kotlin)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/vodka/
│       │   ├── VodkaApp.kt
│       │   ├── ui/               # Compose UI, settings, library
│       │   ├── runtime/          # session supervision, rootfs mgmt
│       │   └── native/           # Kotlin ↔ JNI declarations
│       └── cpp/                  # Bionic C/C++ bridges (JNI)
├── native/                       # Portable bridge sources
│   ├── container/
│   ├── display/
│   ├── input/
│   ├── audio/
│   ├── vulkan-host/
│   └── CMakeLists.txt
├── runtime/
│   ├── fex/                      # patches, build config, FEXConfig profiles
│   ├── wine/                     # build config, winetricks profile
│   ├── dxvk/                     # dll staging
│   ├── rootfs-arm64/             # Dockerfile / debootstrap + overlay
│   └── rootfs-x86_64/            # FEX rootfs + wine prefix
├── tools/
│   ├── fetch-studio/             # downloader (no binaries committed)
│   ├── device-probe/             # kernel/GPU capability reporter
│   └── pack-rootfs/
├── docs/
│   ├── ARCHITECTURE.md           # this file
│   └── DECISIONS/                # ADRs as choices are validated
├── LICENSE                        # GPL-3.0
└── README.md
```

## 8. Roadmap

- **M0 — Foundation.** This doc, repo layout, ADR log, CI skeletons.
- **M1 — Container.** Boot the ARM64 rootfs on a device; run `uname -a`, `bash`, `ls /proc`.
  Validate the `namespaces` vs `proot` matrix. *Exit: shell inside container, no root.*
- **M2 — Emulation.** Run a statically linked x86-64 ELF under FEX inside the container.
  Measure translation throughput. *Exit: x86-64 `hello world`, then a small dynamic binary.*
- **M3 — Windows.** Wine under FEX; `winecfg`/`notepad` window appears on the Android
  Surface through the X server. *Exit: interactive Win32 window on device.*
- **M4 — Graphics.** DXVK + Vulkan thunks; run a D3D11 sample (dxdiag, a known demo).
  *Exit: D3D11 at a measurable, playable FPS on at least one GPU family.*
- **M5 — Studio.** `fetch-studio` downloads and installs Studio into the Wine prefix; it
  reaches the login screen. *Exit: Studio UI renders and accepts input.*
- **M6 — Usable.** Audio, storage round-trip, open a place, playtest. *Exit: edit a part and
  run it.*
- **M7 — Performance.** JIT cache warmup, thunk profiling, driver selection, thermal policy.

## 9. Risks and open questions

| # | Risk | Impact | Mitigation |
|---|------|--------|------------|
| R1 | Kernel blocks user namespaces; only `proot` works | High overhead everywhere | Probe early (M1); budget for proot; optimize hot syscalls |
| R2 | FEX on Android kernel quirks (page size, MTE, pointer auth, seccomp) | FEX won't start | Track FEX Android issues; upstream patches; test matrix of SoCs |
| R3 | Weak mobile Vulkan → DXVK feature-level failure | No rendering | Turnip/PanVK, lavapipe fallback, support DB |
| R4 | Studio's login uses WebView2/.NET | Login impossible | Investigate WebView2 shims; alternate auth flow; known Wine gaps |
| R5 | Thermal/battery: Studio is a desktop workload | Unusable sessions | Frame caps, power profiles, "experimental" expectations |
| R6 | Studio integrity checks reject emulation | Binary refuses to run | Out of our control; document as unsupported |
| R7 | Legal/ToS for running Studio this way | Project risk | No binary redistribution; user-initiated download; clear disclaimer |
| R8 | Split rootfs complexity (ARM64 + x86-64) | Build/release complexity | Automate assembly; pin versions; reproducible CI |

**Open questions.** Minimum viable Android/SDK level; whether FEX can be built cleanly for
Android kernels without Bionic; best presentation path (X11 first vs Wayland first);
whether a single `namespaces`-capable backend can cover enough devices to matter.

## 10. Testing strategy

- **Unit:** Kotlin (JUnit) and native (`gtest`) for bridges and container logic.
- **Integration/host:** `lavapipe` + an x86-64 rootfs on a Linux ARM64 host (or under
  QEMU) to exercise FEX/Wine/DXVK without a phone — slow but deterministic.
- **On-device:** a small device farm spanning Adreno, Mali, and Xclipse, each reporting the
  `device-probe` capability set. Golden-frame screenshots plus a scripted Studio launch.
- **Compatibility matrix:** published per-GPU/per-SoC results, mirroring how the FEX and
  Proton communities report game compatibility.

## 11. Decisions log

Canonical choices, to be expanded into ADRs in `docs/DECISIONS/` as they are validated:

1. **Run x86-64 Wine under FEX**, not ARM64 Wine (§2).
2. **Two rootfs layers** (ARM64 glibc + x86-64 guest), because FEX requires an x86-64 rootfs (§2).
3. **X11 presentation first**, Wayland later (§5.2).
4. **Thunk GPU/audio/display**, never emulate them (§4).
5. **Kotlin for control plane, C/C++ for data plane**, with a daemon boundary (§3).
6. **GPL-3.0**, no Roblox binaries in-repo (§Legal in README).
