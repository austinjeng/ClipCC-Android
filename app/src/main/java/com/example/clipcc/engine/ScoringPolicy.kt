package com.example.clipcc.engine

/**
 * Model-INDEPENDENT scoring/policy defaults, pinned to the Python reference (single source of truth).
 *   THRESHOLD/THRESHOLD_MODE/CONTRAST_* : app/services/temporal_policy.py SigLip2Policy
 *   GAP_TOLERANCE/MIN_DURATION          : app/schemas/response.py ResolvedTemporalOptions
 *   DEFAULT_LABELS                      : app/config.py default_labels
 *   FPS/MAX_FRAMES                      : app/services/video.py
 */
object ScoringPolicy {
    const val THRESHOLD = 0.5
    const val THRESHOLD_MODE = "absolute"
    const val GAP_TOLERANCE = 2.0
    const val MIN_DURATION = 1.0
    const val CONTRAST_THRESHOLD = 0.15
    const val CONTRAST_REDUCE = "mean"
    val CONTRAST_REDUCE_MODES = listOf("mean", "top_k_mean", "max", "quantile")
    const val SCORE_SEMANTICS = "siglip2_pairwise_sigmoid"
    const val FPS = 1.0
    const val MAX_FRAMES = 300
    val DEFAULT_LABELS = listOf(
        "texting while driving", "sleeping while driving", "eating while driving",
    )

    /** Chunked vision-encode size per (model, backend), mirroring the phase-2 CPU_EP batches.
     *  Non-CPU_EP backends encode per-frame; the chunk is then just decode/release granularity (16). */
    fun visionChunkFor(modelId: String, backend: Backend): Int = when {
        backend != Backend.CPU_EP -> 16
        modelId.contains("so400m") -> 4
        modelId.contains("large") -> 8
        else -> 16
    }
}
