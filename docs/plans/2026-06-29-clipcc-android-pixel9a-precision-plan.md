# Plan — Pixel 9a selectable precision (hybrid + override + disclaimers)

Spec: `docs/specs/2026-06-29-clipcc-android-pixel9a-precision-design.md`. TDD where pure-JVM; existing
Plan-1/2/3 parity gates preserved. Tasks ordered by dependency; T1/T2 parallel.

### T0 — Phase-0: so400m fp32 fit probe  *(in flight — download running)*
Add `Fp32FitTest` (instrumented): open BOTH so400m fp32 towers (vision+text, external `.onnx_data`),
run one frame + one label, report survive/OOM. Gates the so400m/fp32 manifest entry (offer+WARN vs grey).
**Gate:** process survives → fits.

### T1 — Precision policy + disclaimer (pure JVM, TDD)
`Precision{FP32,FP16,INT8}`; `ScoringPolicy.recommendedPrecision(modelId, mode)` (argmax→INT8,
threshold→FP16); `PrecisionAdvice.evaluate(modelId, mode, precision) -> Advice{level, text}` (6 rows from
spec §4, copy verbatim). **Tests:** `PrecisionPolicyTest` (model×mode), `PrecisionAdviceTest` (every row
incl. int8+threshold WARN, so400m+fp32 WARN, argmax+fp16 INFO).

### T2 — Manifest v2 (pure JVM, TDD)
Parse `default_precision` + `precisions{prec:{vision,text:{file,bytes,sha256}}}`; `availablePrecisions`,
`filesFor(precision)`; **v1 bundles still parse** (single implicit precision). **Tests:** `ManifestV2Test`
+ existing `ManifestTest` stays green.

### T3 — Engine: resolve towers by precision (device)
`ClassifyRequest` gains `precision`; `RealClassifier`/`Engine.withVisionEncoder` load
`manifest.filesFor(precision)` instead of hardcoded `vision_model.onnx`. Backend path unchanged.
**Gate:** EndToEndParity (default precision per mode) still **0 flips**.

### T4 — UI: precision control + reactive disclaimer (JVM-testable VM)
`SetupState.precision` + `precisionUserSet`; `ClassifyViewModel.setPrecision`; precision **tracks**
recommendation until manual override (then sticky) + "reset to recommended". `SetupCard` segmented control
+ disclaimer text (level-styled: INFO grey / WARN amber). **Tests:** VM default-tracking + override-sticky
+ advice surfaced (plain JVM, platform-light state).

### T5 — Provisioning: multi-precision bundles
Assemble per-model bundle dirs with `vision_model_<prec>.onnx`/`text_model_<prec>.onnx` + v2 manifest
(sha/bytes per file). Push to `filesDir/models/<id>/`. Host export-tooling change (emit v2 multi-precision
bundles) tracked in the Python repo, not here. **Gate:** app lists precisions per model from real bundles.

### T6 — Device gates + suite
EndToEndParity unchanged (0 flips, default precision); add `ClassifyEndToEnd` int8-argmax smoke (real ONNX
over the clip, asserts runs + no disclaimer on default). Full JVM unit suite green.

### T7 — Report + docs
Plan-4 report; update README (precision selector + the recommended-default table); update memory.

**Dependencies:** T1∥T2 → T3 (needs T2), T4 (needs T1,T2) → T5 → T6 → T7. T0 parallel/in-flight.
**Execution:** subagent-driven (project pattern); pure-JVM tasks first (T1,T2) — lowest risk, no device.
