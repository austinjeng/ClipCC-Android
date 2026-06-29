package com.example.clipcc.engine

enum class AdviceLevel { NONE, INFO, WARN }

/** A non-blocking disclaimer for a chosen (model, mode, precision). [text] is empty when [level]==NONE. */
data class PrecisionAdvice(val level: AdviceLevel, val text: String)

/**
 * Android-specific precision policy for Tensor-G4-class CPU, grounded in Spike A/B measurements
 * (`docs/plans/spikeA-*`, `spikeB-*`). `thresholdMode` = the score-thresholding aggregation modes
 * (TEMPORAL/CONTRAST); argmax = MEAN/MAX. Distinct from [ScoringPolicy], which holds the Python-pinned
 * model-independent constants — this object encodes the device precision recommendation + disclaimers.
 */
object PrecisionPolicy {
    /** argmax → int8 (parity-safe + fastest); threshold → fp16 (parity-clean, int8 breaks thresholds). */
    fun recommended(thresholdMode: Boolean): Precision =
        if (thresholdMode) Precision.FP16 else Precision.INT8

    /** Approx int8 vision speedup vs fp16/fp32 on Tensor G4 CPU_EP (Spike B device-latency). */
    private fun int8Speedup(modelId: String): String = when {
        modelId.contains("so400m") -> "~4.5×"
        modelId.contains("large") -> "~4×"
        else -> "~2.8×"
    }

    fun advise(modelId: String, thresholdMode: Boolean, precision: Precision): PrecisionAdvice = when {
        // int8 in a threshold mode → parity risk (measured drift; precedence over INFO notes)
        precision == Precision.INT8 && thresholdMode -> PrecisionAdvice(
            AdviceLevel.WARN,
            "int8 can shift threshold decisions — measured up to 0.18 confidence / 0.03 margin drift vs " +
                "the reference; detections/verdicts may differ from the Python pipeline. " +
                "Use fp16 for threshold parity.",
        )
        // so400m + fp32 → RAM risk (~4.5 GB on an 8 GB device)
        precision == Precision.FP32 && modelId.contains("so400m") -> PrecisionAdvice(
            AdviceLevel.WARN,
            "fp32 so400m needs ~4.5 GB of model memory and may exceed this device's RAM — the run can " +
                "fail. fp16 is the safe high-accuracy choice.",
        )
        // argmax + fp16/fp32 → slower, no decision benefit over int8
        !thresholdMode && (precision == Precision.FP16 || precision == Precision.FP32) -> PrecisionAdvice(
            AdviceLevel.INFO,
            "Best-match results are identical to int8 here — int8 is ${int8Speedup(modelId)} faster with " +
                "no accuracy loss.",
        )
        // any remaining fp32 (e.g. threshold + fp32, non-so400m) → bit-exact note
        precision == Precision.FP32 -> PrecisionAdvice(
            AdviceLevel.INFO,
            "fp32 is bit-exact but uses ~2× the memory of fp16 with no accuracy gain over fp16 for this " +
                "model.",
        )
        // recommended combos (argmax+int8, threshold+fp16) → no disclaimer
        else -> PrecisionAdvice(AdviceLevel.NONE, "")
    }
}
