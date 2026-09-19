# ADR 0002 — Two rootfs layers (ARM64 host + x86-64 guest)

- Status: Accepted
- Date: 2026-09-19
- Supersedes: —

## Context

Android's libc is Bionic, not glibc. FEX, Wine, Mesa, and X11 assume glibc and a `/usr`
layout. FEX additionally documents that it requires an **x86-64 RootFS** to supply x86-64
glibc and guest libraries. We therefore cannot use a single filesystem.

## Decision

Maintain two root filesystems:

1. **ARM64 glibc rootfs** — FEX itself, display server, audio server, GPU drivers.
2. **x86-64 rootfs** — glibc, Wine, DXVK, Windows runtime DLLs, Studio.

## Consequences

- Build and release pipelines must produce and pin both images.
- The container bridge mounts both and sets `FEX_ROOTFS` appropriately.
- The x86-64 rootfs is where the Wine prefix lives; it is writable per-install.
- More moving parts to version and reproduce; mitigated by automated packing in CI.

## Alternatives

- **Bionic-only.** Rejected: porting FEX/Wine/Mesa to Bionic is a multi-year effort.
- **Single unified rootfs.** Rejected: FEX explicitly needs a separate x86-64 rootfs.
