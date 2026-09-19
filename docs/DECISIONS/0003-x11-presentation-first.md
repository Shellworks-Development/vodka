# ADR 0003 — X11 presentation first, Wayland later

- Status: Accepted
- Date: 2026-09-19
- Supersedes: —

## Context

Android has no display server. Wine needs a windowing system, and DXVK's present path
depends on the windowing integration (X11 WSI vs Wayland WSI). We must choose which to
bring up first.

## Decision

Present through an **X server inside the container** whose backing store is an Android
`Surface` (Termux:X11-style, purpose-built). Migrate to Wine's Wayland driver plus a
wlroots compositor with an Android backend after the stack is proven.

## Consequences

- Wine's X11 driver and DXVK's X11 WSI are the most tested combination, reducing unknowns.
- Costs an extra copy/composite relative to a native Wayland path; latency and bandwidth
  are worse but acceptable for bring-up.
- The `display` bridge must own a shared framebuffer and hand it to the Surface, ideally via
  AHardwareBuffer/dmabuf once working.
- Touch input is mapped to pointer events plus a modifier toolbar; hardware input passes
  through.

## Alternatives

- **Wayland-first.** Rejected as the initial target: fewer proven Wine+DXVK on-device
  examples, more compositor work before anything renders.
- **Native Android window for Wine (no server).** Rejected: no such Wine driver exists; would
  require writing a new Wine display driver.
