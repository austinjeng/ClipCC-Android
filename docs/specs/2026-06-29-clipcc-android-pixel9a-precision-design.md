# Pixel 9a — selectable precision (hybrid default + manual override + disclaimers)

**Status:** DRAFT for review · **Date:** 2026-06-29 · supersedes the "fp16-only vs int8" open question.
**Grounding:** Spike A (`spikeA-pixel9a-g4-cpu-baseline.md`), Spike B host-parity + device-latency +
validation (`spikeB-*.md`). Decision: ship **selectable precision** — recommended by default, manually
overridable, with an informative disclaimer when the user leaves the safe path.

## Why (one paragraph)

On Tensor G4 the only real lever is CPU precision. **int8** (MLAS/KleidiAI) is **2.8× (base) → 4.5×
(so400m: 16→3.9 s/frame)** and **parity-safe for argmax (MEAN/MAX)**, but its 0.027 cosine drift × e^4.73
≈ 3 logits → **up to 0.18 confidence / 0.03 margin drift**, which **breaks TEMPORAL (0.5) / CONTRAST
(0.15) thresholds**. **fp16** is parity-clean (4e-5) in all modes. So precision should *follow the mode*
by default, but power users may want to override (e.g. so400m + MEAN + fp32 for a bit-exact baseline).

## 1. Precision model

```
enum Precision { FP32, FP16, INT8 }   // file: vision_model[_fp16|_int8].onnx + text_model[...].onnx
```

**Availability per model** (what we provision; manifest declares it):

| Model | FP32 | FP16 | INT8 | Notes |
|---|:--:|:--:|:--:|---|
| base-256 | ✓ | ✓ | ✓ | fp32 is current default |
| base-384 | ✓ | ✓ | ✓ | |
| large-384 | ✓ (~3.5 GB, fits) | ✓ | ✓ | |
| so400m-384 | ⚠️ (~4.5 GB) | ✓ | ✓ | fp32 RAM-risky — see Phase-0 verify |

## 2. Recommended-precision policy (the smart default)

`ScoringPolicy.recommendedPrecision(modelId, mode) -> Precision`:
- **MEAN / MAX (argmax):** `INT8` — fastest, parity-safe.
- **TEMPORAL / CONTRAST (threshold):** `FP16` — parity-clean reference; int8 unsafe here.

`recommendedBackend` stays as today (CPU_EP is fastest for int8/heavies; orthogonal to precision).

## 3. Default + override behavior

- `SetupState` gains `precision: Precision` and `precisionUserSet: Boolean = false`.
- While `precisionUserSet == false`, precision **tracks the recommendation** — changing model or mode
  re-derives it. Once the user picks precision manually, `precisionUserSet = true` and it **sticks**
  (no silent auto-change); a "reset to recommended" affordance clears it.
- Precision options offered = the model's available precisions (greyed if not provisioned).

## 4. Disclaimer logic (the safety net)

`PrecisionAdvice.evaluate(modelId, mode, precision) -> Advice{level, text}`:

| Chosen | vs recommendation | Level | Disclaimer |
|---|---|---|---|
| argmax + INT8 | = recommended | NONE | — |
| threshold + FP16 | = recommended | NONE | — |
| any + FP32 (fits) | stricter | INFO | "fp32 is bit-exact but uses ~2× the memory of fp16 with no accuracy gain over fp16 for this model." |
| argmax + FP16/FP32 | slower, no gain | INFO | "Best-match results are identical to int8 here — int8 is ~{N}× faster with no accuracy loss." |
| **threshold + INT8** | **unsafe** | **WARN** | "⚠️ int8 can shift threshold decisions. Measured up to **0.18 confidence** / **0.03 margin** drift vs the reference — detections/verdicts may differ from the Python pipeline. Use **fp16** for threshold parity." |
| so400m + FP32 | RAM risk | **WARN** | "⚠️ fp32 so400m needs ~4.5 GB of model memory and may exceed this device's RAM — the run can fail. **fp16** is the safe high-accuracy choice." |

INFO = inline grey helper text. WARN = amber, with an icon, persistent under the selector (non-blocking;
the user may proceed). Copy cites the *measured* numbers (this project's evidence discipline).

## 5. Manifest (schema v2, additive)

Extend the per-model `manifest.json` (bump `schema_version` to 2; engine falls back to v1 = single
precision):
```jsonc
"default_precision": "fp16",
"precisions": {
  "fp16": { "vision": {file,bytes,sha256}, "text": {file,bytes,sha256} },
  "int8": { "vision": {...}, "text": {...} },
  "fp32": { "vision": {...}, "text": {...} }   // omitted where not provisioned
}
```
Files sit in the bundle dir as `vision_model_<prec>.onnx` / `text_model_<prec>.onnx`. `Manifest.kt`
exposes `availablePrecisions` + `filesFor(precision)`. v1 bundles keep working (single implicit precision).

## 6. Engine + UI wiring (minimal, additive — keep Plan-1/2/3 parity gates intact)

- `Manifest`: parse v2 `precisions`; `filesFor(precision)`.
- `RealClassifier` / `Engine.withVisionEncoder`: resolve tower paths from `manifest.filesFor(precision)`
  instead of the hardcoded `vision_model.onnx`. Backend handling unchanged.
- `ScoringPolicy`: `recommendedPrecision`, `PrecisionAdvice`.
- `SetupCard.kt`: a **Precision** segmented control next to Mode; reactive disclaimer text below it;
  "reset to recommended" link when overridden. `ClassifyViewModel`: `setPrecision`, recompute advice.
- `ProvisioningPolicy` / push script: stage all available precision variants per bundle dir.

## 7. Tests

- Pure-JVM: `recommendedPrecision` (model×mode matrix), `PrecisionAdvice` (all 6 rows incl. the int8+
  threshold WARN and so400m+fp32 WARN), `Manifest` v2 parse + v1 fallback.
- Device (unchanged gates): EndToEndParity stays on the **default** precision per mode → **0 flips**
  preserved. Add a smoke asserting int8 path loads + runs for an argmax run.
- No new gate on int8 numerical parity (it is intentionally lossy; the disclaimer is the contract).

## 8. Phase-0 verify (one open item) — RESOLVED 2026-06-29

**Does so400m FP32 fit in 8 GB on the Pixel 9a?** **YES** (measured). `Fp32FitTest` loaded both fp32
towers under the engine residency pattern (encode text → release → open vision; peak ≈ the larger single
tower ~2.83 GB, not the ~4.5 GB sum) and ran a frame each without OOM. → **so400m + fp32 is offered, with
the RAM-risk WARN** (honest: it fit, but it's tight). See `…-precision-report.md`.

## 9. Non-goals

No global settings screen; no change to the recommended policy itself; no per-frame precision; backend
(XNNPACK/CPU_EP) selection unchanged; host export-tooling change (multi-precision bundles) tracked in the
Python repo, not here.

## Resolved (2026-06-29 review)

1. **so400m + fp32:** **Probe first.** Run a one-shot device load of both so400m fp32 towers; if it fits,
   offer with the RAM-risk WARN; if it OOMs, grey it out as "not available on 8 GB". (Phase-0 task below.)
2. **fp32 for base/large:** **Include fp32** as a selectable bit-exact precision wherever provisioned
   (fp32/fp16/int8). fp32 carries the INFO disclaimer ("2× memory, no gain over fp16").
3. **Disclaimer copy:** approved (cites measured drift on purpose).
