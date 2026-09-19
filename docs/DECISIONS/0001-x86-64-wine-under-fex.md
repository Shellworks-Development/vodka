# ADR 0001 — Run x86-64 Wine under FEX

- Status: Accepted
- Date: 2026-09-19
- Supersedes: —

## Context

Roblox Studio ships as x86-64 Windows PE. We must execute it on ARM64 Android. Two shapes
are possible: (a) an ARM64-native Wine that translates PE execution itself, or (b) an
x86-64 Wine executed by a separate instruction translator, with expensive host interfaces
thunked to native code.

## Decision

Run the **entire x86-64 Linux userland, including Wine, under FEX-Emu**, and thunk only
architecture-agnostic interfaces (Vulkan, OpenGL, audio, X11) to native ARM64.

## Consequences

- One ABI boundary (the FEX thunk layer) instead of two; the Windows ABI never crosses
  architectures inside a process.
- We track an x86-64 Wine build and an x86-64 rootfs, not an ARM64 one.
- FEX configuration (thunks, JIT cache, TSO/memory model) becomes a first-class part of the
  project and a primary performance lever.
- We inherit FEX's ARM64 kernel prerequisites and Android porting gaps (see risk R2).

## Alternatives

- **ARM64 Wine + in-process PE translation.** Rejected: requires a Windows-ABI translator
  *and* Wine, doubling the boundary and maintenance surface, with no upstream to lean on.
- **Box64 instead of FEX.** Viable and widely used on Android, but FEX's thunk library and
  Wine/Proton integration are a better fit for GPU-heavy Windows applications.
