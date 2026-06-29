# ClipCC-Android

Native Android (Kotlin/Compose) app benchmarking on-device SigLIP2 video classification,
with full parity to the Python clipCC pipeline. ONNX Runtime Mobile + prebuilt SigLIP2 towers.

Design docs: `docs/specs` + `docs/plans` (Plan 0–3 reports). Host-side export tooling lives in
the Python repo `austinjeng/clipCC` under `tools/android_assets/`.

## Development memory

Carried over from the Python repo where this work started. Read before planning/building.

@docs/memory/clipcc-android-port.md
@docs/memory/clipcc-android-review-discipline.md
