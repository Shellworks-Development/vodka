# ADR 0004 — Thunk GPU/audio/display, never emulate them

- Status: Accepted
- Date: 2026-09-19
- Supersedes: —

## Context

Emulating a GPU driver instruction-by-instruction is not viable. FEX supports forwarding
(thunking) calls from x86-64 guest libraries to native host implementations of the same
API, used in practice for OpenGL, Vulkan, and audio.

## Decision

All GPU, audio, and display work runs **natively on ARM64**. The x86-64 side only makes the
API call; FEX dispatches it to the ARM64 host library.

## Consequences

- Shader compilation, texture upload, and command submission are native; only instruction
  fetch/decode and ABI marshalling are emulated.
- The host must expose compatible `libvulkan.so`, audio, and X11 entrypoints; the
  `vulkan-host` bridge is responsible for the Vulkan side.
- Thunk coverage gaps become hard compatibility failures and must be tracked per API.
- Performance is dominated by translation of application logic, not graphics.

## Alternatives

- **Software-render inside the guest.** Rejected: unusable performance and still needs a
  windowing path.
- **Emulate the driver.** Rejected: infeasible at interactive frame rates.
