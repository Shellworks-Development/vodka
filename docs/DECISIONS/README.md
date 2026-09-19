# Architecture Decision Records

Short, immutable records of choices that shape Vodka. Format: Context → Decision →
Consequences → Alternatives. Once accepted, edit only by adding a superseding ADR.

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-x86-64-wine-under-fex.md) | Run x86-64 Wine under FEX | Accepted |
| [0002](0002-two-rootfs-layers.md) | Two rootfs layers (ARM64 host + x86-64 guest) | Accepted |
| [0003](0003-x11-presentation-first.md) | X11 presentation first, Wayland later | Accepted |
| [0004](0004-thunk-gpu-audio-display.md) | Thunk GPU/audio/display, never emulate them | Accepted |

Decisions 1–6 in `../ARCHITECTURE.md` §11 map onto these ADRs; items not yet recorded here
(control/data plane split, license) are pending.
