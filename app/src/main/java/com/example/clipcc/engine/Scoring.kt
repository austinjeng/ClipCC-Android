package com.example.clipcc.engine

import kotlin.math.ceil
import kotlin.math.exp

// ---- carrier returned by scoreMatrix (Task 7 consumes this exact type) ----
data class ScoreMatrices(val cosine: Array<FloatArray>, val confidence: Array<FloatArray>)

// ---- response-mirror data classes (Double fields; round to 6 dp like Python) ----
data class ScoreItem(
    val label: String,
    val confidence: Double,
    val rawSimilarity: Double,
    val peakFrameIndex: Int? = null,
    val approxTimestampSeconds: Double? = null,
)

data class BestMatch(val label: String, val confidence: Double)

data class FrameScore(val timestamp: Double, val frameIndex: Int, val scores: Map<String, Double>)

data class SegmentStats(
    val activeAvg: Double,
    val intervalAvg: Double,
    val coverageRatio: Double,
    val activeDuration: Double,
)

data class Segment(
    val label: String,
    val startTime: Double,
    val endTime: Double,
    val duration: Double,
    val stats: SegmentStats,
    val peakConfidence: Double,
    val peakTimestamp: Double,
)

data class LabelSummary(
    val label: String,
    val segmentCount: Int,
    val totalActiveDuration: Double,
    val totalSegmentDuration: Double,
    val peakConfidence: Double,
    val durationWeightedConfidence: Double,
)

data class TemporalResult(
    val timeline: List<FrameScore>,
    val segments: List<Segment>,
    val labelSummaries: List<LabelSummary>,
    val bestSegment: Segment?,
    val thresholdMode: String,
    val effectiveThreshold: Double,
    val thresholdWasDefaulted: Boolean,
)

data class ContrastLabelScore(val label: String, val score: Double)

data class ContrastGroupResult(
    val group: String,
    val meanGroupScore: Double,
    val labels: List<ContrastLabelScore>,
)

data class ContrastResult(
    val verdict: String,
    val difference: Double,
    val threshold: Double,
    val thresholdWasDefaulted: Boolean,
    val thresholdSource: String,
    val calibrationStatus: String,
    val contrastReduce: String,
    val positive: ContrastGroupResult,
    val negative: ContrastGroupResult,
    val scoreSemantics: String,
    val labelPooling: String,
    val dominantLabel: String?,
)

data class AggregationResult(
    val scores: List<ScoreItem>,
    val bestMatch: BestMatch,
    val temporal: TemporalResult? = null,
    val contrast: ContrastResult? = null,
)

// ---- timeline (port of frame_timeline.py) ----
data class FrameInterval(val index: Int, val start: Double, val end: Double)

class FrameTimeline(val timestamps: DoubleArray, val fps: Double, val videoDuration: Double) {
    val frameInterval = 1.0 / fps
    val intervals: List<FrameInterval> =
        timestamps.mapIndexed { i, s -> FrameInterval(i, s, minOf(s + frameInterval, videoDuration)) }

    fun timestamp(i: Int): Double = intervals[i].start
    fun gapSeconds(aEndIdx: Int, bStartIdx: Int): Double = intervals[bStartIdx].start - intervals[aEndIdx].end
    fun segmentDuration(startIdx: Int, endIdx: Int): Double = intervals[endIdx].end - intervals[startIdx].start
}

object Scoring {
    const val SCORE_SEMANTICS = "siglip2_pairwise_sigmoid"

    private fun round6(x: Double): Double = Math.round(x * 1_000_000.0) / 1_000_000.0

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /** mean over frames (rows) for column [l]. */
    private fun columnMean(m: Array<FloatArray>, l: Int): Double {
        var sum = 0.0
        for (row in m) sum += row[l].toDouble()
        return sum / m.size
    }

    /**
     * img [F][D] and txt [L][D] are ALREADY L2-normalized (OrtTower normalizes). cosine = img·txtᵀ;
     * confidence = sigmoid(cosine*exp(logitScale) + logitBias). Do NOT re-normalize.
     */
    fun scoreMatrix(
        img: Array<FloatArray>,
        txt: Array<FloatArray>,
        logitScale: Double,
        logitBias: Double,
    ): ScoreMatrices {
        val temperature = exp(logitScale)
        val cosine = Array(img.size) { FloatArray(txt.size) }
        val confidence = Array(img.size) { FloatArray(txt.size) }
        for (f in img.indices) {
            val ivec = img[f]
            for (l in txt.indices) {
                val tvec = txt[l]
                var dot = 0.0
                for (d in ivec.indices) dot += ivec[d].toDouble() * tvec[d].toDouble()
                cosine[f][l] = dot.toFloat()
                confidence[f][l] = sigmoid(dot * temperature + logitBias).toFloat()
            }
        }
        return ScoreMatrices(cosine, confidence)
    }

    fun aggregateMean(
        confidence: Array<FloatArray>,
        rawSimilarity: Array<FloatArray>,
        labels: List<String>,
    ): AggregationResult {
        val scores = labels.indices.map { i ->
            ScoreItem(
                label = labels[i],
                confidence = round6(columnMean(confidence, i)),
                rawSimilarity = round6(columnMean(rawSimilarity, i)),
            )
        }
        val best = scores.maxByOrNull { it.confidence }!!
        return AggregationResult(scores = scores, bestMatch = BestMatch(best.label, best.confidence))
    }

    fun aggregateMax(
        confidence: Array<FloatArray>,
        rawSimilarity: Array<FloatArray>,
        labels: List<String>,
        frameTimestamps: DoubleArray,
    ): AggregationResult {
        val scores = labels.indices.map { l ->
            var peakIdx = 0
            var peakVal = confidence[0][l]
            for (f in confidence.indices) {
                if (confidence[f][l] > peakVal) {
                    peakVal = confidence[f][l]
                    peakIdx = f
                }
            }
            ScoreItem(
                label = labels[l],
                confidence = round6(peakVal.toDouble()),
                rawSimilarity = round6(rawSimilarity[peakIdx][l].toDouble()),
                peakFrameIndex = peakIdx,
                approxTimestampSeconds = frameTimestamps[peakIdx],
            )
        }
        val best = scores.maxByOrNull { it.confidence }!!
        return AggregationResult(scores = scores, bestMatch = BestMatch(best.label, best.confidence))
    }

    fun aggregateTemporal(
        confidence: Array<FloatArray>,
        rawSimilarity: Array<FloatArray>,
        labels: List<String>,
        threshold: Double,
        gapTol: Double,
        minDur: Double,
        timeline: FrameTimeline,
        thresholdWasDefaulted: Boolean,
    ): AggregationResult {
        val nFrames = confidence.size

        // detection scores = confidence (SigLip2Policy.detection_scores)
        val timelineEntries = (0 until nFrames).map { i ->
            val scoresDict = labels.indices.associate { j -> labels[j] to round6(confidence[i][j].toDouble()) }
            FrameScore(timestamp = timeline.timestamp(i), frameIndex = i, scores = scoresDict)
        }

        val allSegments = mutableListOf<Segment>()
        for (j in labels.indices) {
            val label = labels[j]
            val mask = BooleanArray(nFrames) { confidence[it][j] >= threshold }

            // build raw contiguous [start, end] index runs
            val raw = mutableListOf<Pair<Int, Int>>()
            var inSeg = false
            var startIdx = 0
            for (i in 0 until nFrames) {
                if (mask[i] && !inSeg) {
                    startIdx = i
                    inSeg = true
                } else if (!mask[i] && inSeg) {
                    raw.add(startIdx to (i - 1))
                    inSeg = false
                }
            }
            if (inSeg) raw.add(startIdx to (nFrames - 1))

            // merge consecutive runs when gap <= gapTol
            val merged = mutableListOf<Pair<Int, Int>>()
            for (seg in raw) {
                val last = merged.lastOrNull()
                if (last != null && timeline.gapSeconds(last.second, seg.first) <= gapTol) {
                    merged[merged.size - 1] = last.first to seg.second
                } else {
                    merged.add(seg)
                }
            }

            for ((start, end) in merged) {
                val duration = timeline.segmentDuration(start, end)
                if (duration < minDur) continue

                val intervalLen = end - start + 1
                val intervalScores = DoubleArray(intervalLen) { confidence[start + it][j].toDouble() }
                val activeMask = BooleanArray(intervalLen) { intervalScores[it] >= threshold }

                var activeSum = 0.0
                var activeCount = 0
                var intervalSum = 0.0
                var activeDur = 0.0
                var peakVal = intervalScores[0]
                var peakRel = 0
                for (k in 0 until intervalLen) {
                    intervalSum += intervalScores[k]
                    if (activeMask[k]) {
                        activeSum += intervalScores[k]
                        activeCount++
                        activeDur += timeline.intervals[start + k].end - timeline.intervals[start + k].start
                    }
                    if (intervalScores[k] > peakVal) {
                        peakVal = intervalScores[k]
                        peakRel = k
                    }
                }
                val peakIdx = start + peakRel

                allSegments.add(
                    Segment(
                        label = label,
                        startTime = timeline.timestamp(start),
                        endTime = timeline.intervals[end].end,
                        duration = round6(duration),
                        stats = SegmentStats(
                            activeAvg = round6(activeSum / activeCount),
                            intervalAvg = round6(intervalSum / intervalLen),
                            coverageRatio = round6(activeCount.toDouble() / intervalLen),
                            activeDuration = round6(activeDur),
                        ),
                        peakConfidence = round6(peakVal),
                        peakTimestamp = timeline.timestamp(peakIdx),
                    )
                )
            }
        }

        val labelSummaries = labels.map { label ->
            val labelSegs = allSegments.filter { it.label == label }
            val totalActive = labelSegs.sumOf { it.stats.activeDuration }
            val totalSegment = labelSegs.sumOf { it.duration }
            val peak = labelSegs.maxOfOrNull { it.peakConfidence } ?: 0.0
            val dwc = if (totalActive > 0) {
                labelSegs.sumOf { it.stats.activeDuration * it.stats.activeAvg } / totalActive
            } else {
                0.0
            }
            LabelSummary(
                label = label,
                segmentCount = labelSegs.size,
                totalActiveDuration = round6(totalActive),
                totalSegmentDuration = round6(totalSegment),
                peakConfidence = round6(peak),
                durationWeightedConfidence = round6(dwc),
            )
        }

        val bestSegment = allSegments.maxByOrNull { it.peakConfidence }

        val meanResult = aggregateMean(confidence, rawSimilarity, labels)

        return AggregationResult(
            scores = meanResult.scores,
            bestMatch = meanResult.bestMatch,
            temporal = TemporalResult(
                timeline = timelineEntries,
                segments = allSegments,
                labelSummaries = labelSummaries,
                bestSegment = bestSegment,
                thresholdMode = "absolute",
                effectiveThreshold = threshold,
                thresholdWasDefaulted = thresholdWasDefaulted,
            ),
        )
    }

    fun aggregateContrast(
        confidence: Array<FloatArray>,
        rawSimilarity: Array<FloatArray>,
        labels: List<String>,
        posCount: Int,
        reduce: String,
        threshold: Double,
        thresholdWasDefaulted: Boolean = true,
        thresholdSource: String = "model_policy",
        calibrationStatus: String = "uncalibrated",
    ): AggregationResult {
        val nFrames = confidence.size
        val nLabels = labels.size

        val framePos = DoubleArray(nFrames) { f ->
            var s = 0.0
            for (l in 0 until posCount) s += confidence[f][l].toDouble()
            s / posCount
        }
        val frameNeg = DoubleArray(nFrames) { f ->
            var s = 0.0
            for (l in posCount until nLabels) s += confidence[f][l].toDouble()
            s / (nLabels - posCount)
        }

        val frameMargins = FloatArray(nFrames) { (framePos[it] - frameNeg[it]).toFloat() }
        val videoMargin = contrastReduce(frameMargins, reduce)

        val verdict = when {
            videoMargin > threshold -> "positive"
            videoMargin < -threshold -> "negative"
            else -> "uncertain"
        }

        val meanPos = framePos.average()
        val meanNeg = frameNeg.average()

        val posLabelScores = (0 until posCount).map {
            ContrastLabelScore(labels[it], round6(columnMean(confidence, it)))
        }
        val negLabelScores = (posCount until nLabels).map {
            ContrastLabelScore(labels[it], round6(columnMean(confidence, it)))
        }

        val dominantLabel = when (verdict) {
            "uncertain" -> null
            "positive" -> posLabelScores.maxByOrNull { it.score }!!.label
            else -> negLabelScores.maxByOrNull { it.score }!!.label
        }

        val contrastResult = ContrastResult(
            verdict = verdict,
            difference = round6(videoMargin),
            threshold = threshold,
            thresholdWasDefaulted = thresholdWasDefaulted,
            thresholdSource = thresholdSource,
            calibrationStatus = calibrationStatus,
            contrastReduce = reduce,
            positive = ContrastGroupResult("positive", round6(meanPos), posLabelScores),
            negative = ContrastGroupResult("negative", round6(meanNeg), negLabelScores),
            scoreSemantics = SCORE_SEMANTICS,
            labelPooling = "mean",
            dominantLabel = dominantLabel,
        )

        val base = aggregateMean(confidence, rawSimilarity, labels)
        return AggregationResult(scores = base.scores, bestMatch = base.bestMatch, contrast = contrastResult)
    }

    /** Port of contrast_reduce. Exposed internal for direct unit testing. */
    internal fun contrastReduce(margins: FloatArray, mode: String): Double = when (mode) {
        "mean" -> margins.fold(0.0) { acc, v -> acc + v } / margins.size
        "top_k_mean" -> {
            val k = maxOf(1, ceil(margins.size * 0.10).toInt())
            // pick the k indices with largest ABSOLUTE value; return mean of SIGNED margins at those indices
            val topIdx = margins.indices.sortedByDescending { kotlin.math.abs(margins[it]) }.take(k)
            topIdx.fold(0.0) { acc, i -> acc + margins[i] } / k
        }
        "max" -> {
            var bestIdx = 0
            var bestAbs = kotlin.math.abs(margins[0])
            for (i in margins.indices) {
                val a = kotlin.math.abs(margins[i])
                if (a > bestAbs) {
                    bestAbs = a
                    bestIdx = i
                }
            }
            margins[bestIdx].toDouble()
        }
        "quantile" -> {
            val pos = quantile(margins, 0.90)
            val neg = quantile(margins, 0.10)
            if (kotlin.math.abs(pos) >= kotlin.math.abs(neg)) pos else neg
        }
        else -> throw IllegalArgumentException("Unknown contrast reduction mode: $mode")
    }

    /** Linear-interpolation quantile matching numpy/torch default. */
    private fun quantile(margins: FloatArray, q: Double): Double {
        val sorted = margins.map { it.toDouble() }.sorted()
        val n = sorted.size
        if (n == 1) return sorted[0]
        val pos = q * (n - 1)
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt()
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo])
    }
}
