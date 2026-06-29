# Spike B (host parity) — SigLIP2 quantization vs golden

**Date:** 2026-06-28 · host-side, no device · ONNX Runtime 1.26.0 (`.venv-export`, CPU EP)
**Method:** base-256 quant ladder from `onnx-community/siglip2-base-patch16-256-ONNX`, run on the
**identical golden inputs** (`preprocess_golden.npz` pixel_values [2,3,256,256] + `tokenizer_golden.json`
input_ids), embedding = `pooler_output` **by name**, L2-normalized, scored with manifest
logit_scale/bias. Compared to `fixtures/scores_golden.json` (fp32 PyTorch reference).
Gate (Plan-1): cosine_max ≤ 0.01 **and** 0 best-match flips.

## Result

| Precision | vis MB | txt MB | cosine_max | conf_max | flips | Gate |
|---|---:|---:|---:|---:|---:|:--|
| fp32 (local, sanity) | 372 | 1129 | 1.96e-07 | 2.3e-09 | 0 | PASS |
| **fp16** | 187 | 565 | **4.26e-05** | 9.6e-07 | 0 | **PASS** |
| q4 | 63 | 843 | 1.29e-02 | 7.3e-05 | 0 | FAIL |
| q4f16 | — | — | 1.29e-02 | 7.4e-05 | 0 | FAIL |
| int8 | 95 | 283 | 2.70e-02 | 2.4e-05 | 0 | FAIL |
| quantized (uint8 dyn) | 95 | 283 | 2.65e-02 | 3.7e-05 | 0 | FAIL |
| uint8 | 95 | 283 | 2.82e-02 | 4.6e-05 | 0 | FAIL |

(fp16 & q4f16 need `graph_optimization_level=DISABLE_ALL` to load on ORT-desktop 1.26 — a
`SimplifiedLayerNormFusion` graph bug on fp16 graphs; the mobile package runs fp16 fine on-device.)

## Findings

1. **Harness validated.** fp32 reproduces golden cosine to **1.96e-07** — the exact value Plan-1's host
   check reported (`pooler_output` repro 1.96e-7). The parity rig is correct.
2. **fp16 is parity-clean (4.3e-05).** Confirms host-side that fp16 — the current device precision for
   large/so400m — preserves SigLIP2 embeddings. fp16 stays the safe baseline.
3. **All int8-class quantizations FAIL the ≤0.01 gate (~0.027, ~3× over).** int8/uint8/quantized drift
   ~0.027 in cosine; q4/q4f16 milder (~0.013, just over). This is **expected** — the ≤0.01 gate is a
   *numerical-equivalence* gate (right for fp16), and int8 is lossy by design. Failing it is not by
   itself disqualifying; it just means int8 ≠ fp16-exact.
4. **Decisions held on this clip — but the sample is thin.** 0 best-match flips and confidence drift
   ≤ 5e-05 for every precision. BUT the golden is **2 frames × 3 labels** — far too small to trust a
   "preserves classification" claim. The robust, content-light number is the **cosine drift (~0.027)**;
   the 0-flips is encouraging but under-powered.

## Caveats / limits

- Golden exists **only for base-256**. int8 parity on base-384/large/so400m is unmeasured (no torch
  golden). The bandwidth-bound fp16 heavies — where int8 could help most (halves weight traffic) — are
  exactly where parity is unverified.
- **Latency benefit is unmeasured and uncertain.** Whether int8 actually runs faster on the G4 CPU is
  open: ORT-Mobile XNNPACK may not route these QDQ/dynamic-int8 graphs and could silently dequantize to
  fp32 (no speedup). The research flagged this explicitly.

## Conclusion → next step

int8 **does not** hold SigLIP2's strict numerical-parity gate (~3× over), though it preserves top-1 on a
thin clip. Before investing in a larger parity-validation set, **fail-fast on latency**: measure int8
base-256 ms/frame on the Pixel 9a (XNNPACK + CPU_EP) and confirm provider routing (int8 kernels vs silent
dequant). If int8 isn't even faster on G4, the parity question is moot and **fp16 stands as the Pixel 9a
precision**. If it is faster, then expand the parity validation (more frames/labels, per-model goldens)
to decide whether the ~0.027 drift is acceptable for the app's decision/confidence needs.

Script: `scratchpad/spikeB_parity.py`.

## Validation (2026-06-29) — int8 decision-flips across all 4 aggregation modes

The Android app (`RealClassifier.kt`) ships **MEAN, MAX, TEMPORAL, CONTRAST** modes. TEMPORAL thresholds
sigmoid at **0.5**; CONTRAST thresholds a group-margin at **0.15** (`ScoringPolicy`). Validated base-256
fp32-ONNX vs int8 (both towers) on **24 real driving frames × the 3 default labels + 3 negatives**:

- cosine drift 0.027 → **confidence drift up to 0.184** (3.05 logits worst cell — `0.027 × e^4.73`).
- **MEAN / MAX (argmax): no flip** — int8 preserves the winning label (inter-label margins ≫ drift).
- **TEMPORAL: 0 flips on this clip — but only because no score reached 0.5** (0 detections either way).
- **CONTRAST: no verdict flip, but the video-margin moved 0.031** (~20% of the 0.15 threshold).

**Verdict:** int8 is **parity-safe for MEAN/MAX (argmax)** — decisions preserved, confidence cosmetic.
int8 is **NOT parity-safe for TEMPORAL/CONTRAST** — the ~0.18 confidence swing / ~0.03 margin swing are
large vs the 0.5 / 0.15 thresholds, so int8 *will* flip detection/verdict for any footage whose true
scores sit near a boundary. It didn't trigger on this benign clip (scores far from thresholds), but the
drift magnitude proves the mechanism is live. fp16 stays parity-safe for ALL four modes (4e-5).

Script: `scratchpad/spikeB_validate.py`.
