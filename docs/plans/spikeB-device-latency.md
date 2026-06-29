# Spike B (device latency) — int8 fail-fast on Pixel 9a / Tensor G4

**Date:** 2026-06-29 · device · `QuantProbeTest`, base-256 **vision** tower, 16 dummy frames, median-of-3
+ warm-up. fp32 measured through the SAME probe → apples-to-apples. Provider node counts from ORT
profiling detect silent dequant-to-fp32. ORT 1.26.0.

## Result (ms/frame)

| Precision | Backend | ms/frame | vs fp32 | totalNodes | providers |
|---|---|---:|---:|---:|---|
| fp32 | CPU_EP | 713 | 1.00× | 475 | CPU 475 |
| fp32 | CPU_XNNPACK | 1434 | 1.00× | 760 | CPU 670, Xnnpack 90 |
| **int8** | **CPU_EP** | **261** | **2.73×** | 430 | CPU 430 |
| int8 | CPU_XNNPACK | 323 | 4.44× | 701 | CPU 688, Xnnpack 13 |
| **quantized** (uint8 dyn) | **CPU_EP** | **249** | **2.86×** | 430 | CPU 430 |
| quantized | CPU_XNNPACK | 324 | 4.43× | 701 | CPU 688, Xnnpack 13 |
| q4 | CPU_XNNPACK | 645 | 2.22× | 684 | CPU 670, Xnnpack 14 |
| q4 | CPU_EP | 802 | **0.89× (slower)** | 413 | CPU 413 |

(fp32 probe numbers — XNNPACK 1434 / CPU_EP 713 — match the Spike A full benchmark base-256 — 1491 / 745
— validating the probe.)

## Findings

1. **int8 is a real ~2.8× win on G4, NOT a silent dequant.** Best int8/uint8 = **249–261 ms/frame
   (CPU_EP)** vs fp32 best 713 → base-256 vision goes ~1.4 fps → **~4 fps**. The research's "XNNPACK may
   silently dequantize → no speedup" risk is **disproven** for int8/uint8 on the CPU EP.
2. **The speedup is MLAS, not XNNPACK.** Under the XNNPACK backend, int8 delegates *fewer* nodes to
   Xnnpack (13 vs fp32's 90) — the int8 GEMM runs on `CPUExecutionProvider` = MLAS, which has int8
   kernels accelerated by KleidiAI on the Armv9 Cortex-X4. The clean, decision-relevant number is
   **CPU_EP = 2.8×**.
3. **q4 is a trap on CPU_EP (0.89× — slower).** 4-bit MatMulNBits dequantizes to float at matmul time →
   no GEMM win, extra overhead. Exactly the research prediction. **int8/uint8 are the winners; q4 is not.**

## Verdict & where this leaves int8

Fail-fast PASSED: int8 delivers a large, genuine speedup on G4. Combined with Spike B host parity (int8
fails the strict ≤0.01 numerical gate at ~0.027 cosine drift, but 0 flips on the thin 2-frame golden),
the int8 lever is now **live and material**: ~2.8× faster at the cost of measurable embedding drift.

So the deferred product question is now the deciding one: **does Classify need exact embedding/score
parity with the Python pipeline, or only correct decisions?**
- Exact parity → int8 out; **fp16 stands** as the Pixel 9a precision (Spike A: 1.1–1.6× over 7a). Done.
- Decisions-only → int8 is worth committing to, pending **two confirmations**: (a) a real parity
  validation (more frames/labels, per-model goldens) proving decisions hold at ~0.027 drift, and
  (b) int8 latency on the **bandwidth-bound large/so400m** towers (int8 halves weight traffic → may
  exceed 2.8× exactly where it matters most: so400m's ~16 s/frame).

Probe: `QuantProbeTest.kt`; raw `scratchpad/spikeB_lat/quantprobe_*.json`.

## Heavy models (large / so400m) — int8 vs fp32, same probe

| Model | Backend | fp32 ms/fr | int8 ms/fr | Speedup |
|---|---|---:|---:|---:|
| large | CPU_EP | 10020 | 2356 | **4.25×** |
| large | CPU_XNNPACK | 7636 | 2675 | 2.85× |
| so400m | CPU_EP | 17547 | **3895** | **4.51×** |
| so400m | CPU_XNNPACK | 15326 | 4489 | 3.41× |

int8 helps the **bandwidth-bound heavies even more than base** (4.25–4.51× CPU_EP vs base's 2.8×) — int8
halves weight memory traffic, the dominant cost there. **so400m: ~16–18 → ~3.9 s/frame; large: ~10 →
~2.4 s/frame.** Routing: CPU_EP = all CPUExecutionProvider (MLAS int8/KleidiAI), XNNPACK delegates few
nodes — genuine acceleration, no silent dequant. (Probe fp32 differs slightly from Spike A's full-bench
fp32 due to frame count/thermal; the in-probe int8/fp32 ratio is the clean comparison.)

**So the int8 speed upside is largest exactly on the heavy models — but per the parity validation it is
locked to MEAN/MAX (argmax) modes; it breaks TEMPORAL/CONTRAST.** The decision reduces to: do the heavy
models ever run in argmax mode (→ int8 fast-mode worth ~4.5×) or always temporal/contrast (→ fp16 only).
