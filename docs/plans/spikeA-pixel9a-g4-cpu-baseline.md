# Spike A — Pixel 9a / Tensor G4 CPU baseline

**Date:** 2026-06-28 · **Device:** Pixel 9a (`tegu`), Google Tensor G4, Android 16 (SDK 36), 7.75 GB RAM, on USB charger
**Stack:** unchanged engine, ONNX Runtime Mobile **1.26.0** (KleidiAI Armv9 micro-kernels already in since ORT 1.22), `benchmark-v1` profile
**Method:** `BenchmarkMatrixTest`, one `am instrument` per model (fresh process), median-of-3 vision with warm-up + 1.5 s cool-downs. Test clip = 30 s 640×480 H.264. Frames timed: base/large = 16, so400m = 4. Compared on **ms/frame** (frame-count-independent) vs the Plan-2 Pixel 7a / Tensor G2 result.

## Result — G4 vs G2 (vision ms/frame)

| Model | Backend | G4 ms/fr | G2 ms/fr | Speedup |
|---|---|---:|---:|---:|
| base-256 | CPU_XNNPACK | 1491 | 2372 | **1.59×** |
| base-256 | CPU_EP (b16) | **745** | 1202 | **1.61×** |
| base-384 | CPU_XNNPACK | 3474 | 5520 | **1.59×** |
| base-384 | CPU_EP (b16) | 2038 | 2966 | 1.46× |
| large-384 | CPU_XNNPACK | 9686 | 10678 | 1.10× |
| large-384 | CPU_EP (b8) | 9719 | 11179 | 1.15× |
| so400m | CPU_XNNPACK | **16384** | 17880 | 1.09× |
| so400m | CPU_EP (b4) | 16774 | 19190 | 1.14× |

## Findings

1. **The CPU uplift is precision-split, not uniform.** Small **fp32** models gain a clean **~1.5–1.6×** from the Cortex-X4 (vs G2's X1). Big **fp16** models (large, so400m) gain only **~1.1×** — they are **memory-bandwidth-bound**, not core-bound, so the faster cores barely help. Same 8 GB LPDDR5X class as the 7a → bandwidth ceiling carries over. This matches the research prediction exactly.

2. **so400m is still ~16 s/frame.** 16.4 s/frame (XNNPACK) on G4 vs 17.9 on G2. Faster, still nowhere near interactive. The only config approaching usable is **base-256 CPU_EP at 745 ms/frame (~1.3 fps)**.

3. **NNAPI = 0% delegated on Tensor G4 — measured, first direct G4 data.** Both `NNAPI_DEFAULT` and `NNAPI_CPU_DISABLED`, all 4 models: **every node `CPUExecutionProvider`, HW nodes = 0** (base 475/475, large 2072/2072, so400m 2318/2318). Confirms the research verdict on real hardware: the G4 TPU is unreachable for custom models; NNAPI is silent CPU fallback. (Android 16 — NNAPI deprecated since 15.)

4. **XNNPACK delegates 9–12%** of vision nodes (base 12%, large/so400m 9%) — identical to G2. No change.

5. **CPU_EP vs XNNPACK flipped slightly.** CPU_EP batching still ~2× on small fp32 (base-256: 745 vs 1491). On the big fp16 models the two are now a **wash** (XNNPACK marginally ahead), where G2 favored XNNPACK more clearly.

6. **No thermal throttle observed — but this did NOT test sustained load.** `thermalThrottled=false` every run; status rose only to 1 (LIGHT) on large/so400m. ⚠️ Caveat: median-of-3 bursts with 1.5 s cool-downs and a fresh process per model is **not** a sustained continuous run, and the device was **on charger**. The research's "~50% throttle after ~15 min sustained" is **neither confirmed nor refuted** here — it remains a separate open test (dedicated sustained-loop, off-charger).

## Conclusion

Spike A confirms the research on real G4 silicon: **Pixel 9a is the same CPU-only SigLIP2 story as the 7a, just incrementally faster** (1.1–1.6×, biggest on small fp32, smallest on the fp16 heavies). No accelerator path opened — NNAPI still 0%. The faster CPU does not change the qualitative picture: large/so400m stay seconds-per-frame.

→ The remaining lever to keep SigLIP2 *and* get a real speedup is **quantization (Spike B)** — int8/q4, which KleidiAI accelerates on Armv9. Proceed to Spike B: parity (cosine vs `scores_golden.json`) + int8-vs-fp16 latency, and confirm XNNPACK actually routes int8 (QC8/QU8) vs silently dequantizing.

Raw: `docs/plans/spikeA-pixel9a-g4-benchmark-result.json` (8 runs, 24 capability probes).
