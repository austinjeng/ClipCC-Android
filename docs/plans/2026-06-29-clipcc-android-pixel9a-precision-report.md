# Plan report — Pixel 9a selectable precision (hybrid + override + disclaimers)

**Date:** 2026-06-29 · device: Pixel 9a (Tensor G4, Android 16, 8 GB) · ORT 1.26.0
Spec: `docs/specs/2026-06-29-clipcc-android-pixel9a-precision-design.md`. Plan:
`docs/plans/2026-06-29-clipcc-android-pixel9a-precision-plan.md`. Grounding spikes: `spikeA-*`, `spikeB-*`.

## What shipped

Selectable per-run precision (fp32 / fp16 / int8), **recommended by default, manually overridable, with
disclaimers that cite the measured drift**:
- **Smart default** — precision follows aggregation mode: argmax (MEAN/MAX) → **int8** (2.8–4.5× faster,
  parity-safe); threshold (TEMPORAL/CONTRAST) → **fp16** (bit-exact). Tracks the recommendation until the
  user overrides, then sticks; "↺ recommended" resets.
- **Manual override** — a Precision segmented control in Setup; precisions not provisioned for a model are
  greyed. Any combination is allowed, including **so400m + MEAN + fp32**.
- **Disclaimers** (non-blocking, measured): int8+threshold ⚠️ (0.18 conf / 0.03 margin drift), so400m+fp32
  ⚠️ (~4.5 GB RAM risk), argmax+fp16/fp32 ℹ️ (no gain over int8).

## Tasks

| Task | Outcome |
|---|---|
| T1 policy + disclaimer copy | `Precision`, `PrecisionPolicy` (separate from Python-pinned `ScoringPolicy`) — `PrecisionPolicyTest` 6/6 |
| T2 manifest v2 | `precisions` map + `default_precision`; `availablePrecisions`/`filesFor`; v1 fallback — `ManifestV2Test` 4/4 |
| T3 engine by precision | `Engine(precision=…)` via `filesFor`; request carries it; ViewModel recommends; RealClassifier clamps |
| T4 UI override | `ModelInfo.availablePrecisions`; SetupState + VM tracking (sticky/clamped) + persistence; SetupCard control + reactive disclaimer — `ClassifyViewModelTest` +6 |
| T5 provisioning | base-256 v2 multi-precision bundle assembled (fp32/fp16/int8) → device; live `filesDir` provisioning streamed via run-as |
| T6 device gates | **all green** (below) |

## Verification

**JVM unit: 75 tests, 0 failures** (was 59 pre-feature → +16).

**Device gates (Pixel 9a):**
- **PrecisionEngineTest — OK.** A v2 bundle resolves towers by precision and runs real ONNX inference;
  int8 genuinely executes (differs from fp32) yet stays sane (cosine Δ < 0.1, ~0.03 expected).
- **Fp32FitTest — OK. so400m fp32 FITS in 8 GB** under the engine residency pattern (encode text → release
  → open vision; peak ≈ the larger single tower ~2.83 GB, not the ~4.5 GB sum). No OOM. → so400m+fp32 is
  offered with the RAM disclaimer (honest: it fit here, but it's tight).
- **EndToEndParityTest — OK (2 tests).** The precision refactor did **not** disturb default-precision
  parity; the 0-flip gate holds.

## Measured payoff (Spike B, recap)

int8 vision ms/frame on G4 CPU_EP: base 745→**261** (2.8×), large 10020→**2356** (4.25×), so400m
17547→**3895** (4.5×, ~16–18→~3.9 s/frame). Parity-safe for argmax; breaks thresholding (hence the
mode-following default + the WARN).

## Remaining (deploy / manual)

- **Full multi-precision provisioning** for large/so400m into `filesDir/models` (base-256 done this
  session). Same assembly recipe (`scratchpad/v2bundle` + per-precision files from onnx-community).
- **Host export tooling** — teach `tools/android_assets/export_models.py` (Python repo) to emit v2
  multi-precision bundles + manifests, so provisioning isn't hand-assembled.
- **Manual UI screenshot** — pick a video via SAF, exercise the precision control + disclaimers (picker
  not scriptable; same manual step as Plan 3).
