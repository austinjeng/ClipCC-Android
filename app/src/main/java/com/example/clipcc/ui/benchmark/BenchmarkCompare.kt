package com.example.clipcc.ui.benchmark

/** One backend lane (e.g. CPU_XNNPACK) compared across the two devices. */
data class LaneDelta(
    val backend: String,
    val ms7a: Double, val ms7aMin: Double, val ms7aMax: Double,
    val ms9a: Double, val ms9aMin: Double, val ms9aMax: Double,
    val pctFaster: Double, val speedup: Double,
    val protocolMatched: Boolean, val bandSeparated: Boolean,
)
data class ModelDelta(
    val modelId: String, val frames7a: Int?, val frames9a: Int?,
    val lanes: List<LaneDelta>, val headline: LaneDelta?,
)
data class Comparison(
    val header: String, val models: List<ModelDelta>,
    val avgPctFaster: Double, val minPctFaster: Double, val maxPctFaster: Double, val heroLaneCount: Int,
)
enum class EmptyReason { NO_9A, NO_7A, NO_OVERLAP }

sealed interface CompareResult {
    data class Ok(val comparison: Comparison) : CompareResult
    data class Empty(val reason: EmptyReason) : CompareResult
}

object BenchmarkCompare {

    /** Pure join of two already-parsed snapshots. */
    fun build(
        sevenA: Pair<List<ModelGroup>, SnapshotMeta>,
        nineA: Pair<List<ModelGroup>, SnapshotMeta>,
    ): Comparison {
        val (g7, m7) = sevenA
        val (g9, m9) = nineA
        val by9 = g9.associateBy { it.modelId }
        val models = ArrayList<ModelDelta>()
        for (mg7 in g7) {
            val mg9 = by9[mg7.modelId] ?: continue
            val f7 = m7.framesByModel[mg7.modelId]
            val f9 = m9.framesByModel[mg7.modelId]
            val lanes9 = mg9.timed.associateBy { it.backend }
            val lanes = ArrayList<LaneDelta>()
            for (t7 in mg7.timed) {
                val t9 = lanes9[t7.backend] ?: continue
                val matched = t7.backend == "CPU_XNNPACK" || (f7 != null && f9 != null && f7 == f9)
                val pct = if (t7.msPerFrame > 0) (t7.msPerFrame - t9.msPerFrame) / t7.msPerFrame * 100 else 0.0
                val speed = if (t9.msPerFrame > 0) t7.msPerFrame / t9.msPerFrame else 0.0
                lanes.add(LaneDelta(
                    backend = t7.backend,
                    ms7a = t7.msPerFrame, ms7aMin = t7.msPerFrameMin, ms7aMax = t7.msPerFrameMax,
                    ms9a = t9.msPerFrame, ms9aMin = t9.msPerFrameMin, ms9aMax = t9.msPerFrameMax,
                    pctFaster = pct, speedup = speed,
                    protocolMatched = matched,
                    bandSeparated = t9.msPerFrameMax < t7.msPerFrameMin,
                ))
            }
            if (lanes.isEmpty()) continue
            models.add(ModelDelta(
                modelId = mg7.modelId, frames7a = f7, frames9a = f9, lanes = lanes,
                headline = lanes.filter { it.protocolMatched }.minByOrNull { it.ms9a },
            ))
        }
        val heads = models.mapNotNull { it.headline?.pctFaster }
        val soc9 = m9.soc ?: m9.deviceModel ?: "Tensor G4"
        val soc7 = m7.soc ?: "Tensor G2"
        val ort = m9.ort ?: m7.ort ?: "1.26.0"
        val header = "Pixel 9a · $soc9  vs  Pixel 7a · $soc7 · CPU-only · ms/frame (vision-encode) · ORT $ort"
        return Comparison(
            header = header, models = models,
            avgPctFaster = if (heads.isEmpty()) 0.0 else heads.average(),
            minPctFaster = heads.minOrNull() ?: 0.0,
            maxPctFaster = heads.maxOrNull() ?: 0.0,
            heroLaneCount = heads.size,
        )
    }

    /** Read + parse both snapshots, classifying failures. [read7a]/[read9a] return raw JSON or null
     *  (asset missing); they may throw on malformed JSON — caught here. */
    fun load(read7a: () -> String?, read9a: () -> String?): CompareResult {
        val s7 = parseSnapshot(read7a) ?: return CompareResult.Empty(EmptyReason.NO_7A)
        val s9 = parseSnapshot(read9a) ?: return CompareResult.Empty(EmptyReason.NO_9A)
        val cmp = build(s7, s9)
        return if (cmp.models.isEmpty()) CompareResult.Empty(EmptyReason.NO_OVERLAP)
        else CompareResult.Ok(cmp)
    }

    private fun parseSnapshot(read: () -> String?): Pair<List<ModelGroup>, SnapshotMeta>? =
        runCatching {
            val json = read() ?: return@runCatching null
            BenchmarkData.parse(json) to BenchmarkData.parseMeta(json)
        }.getOrNull()
}
