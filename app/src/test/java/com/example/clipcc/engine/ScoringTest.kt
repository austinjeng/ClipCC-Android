package com.example.clipcc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {

    private fun rows(vararg vals: FloatArray): Array<FloatArray> = arrayOf(*vals)
    private fun col(vararg vals: Float): Array<FloatArray> = Array(vals.size) { floatArrayOf(vals[it]) }
    private fun timelineFor(conf: Array<FloatArray>, videoDuration: Double): FrameTimeline {
        val ts = DoubleArray(conf.size) { it.toDouble() }
        return FrameTimeline(ts, 1.0, videoDuration)
    }

    // ---- scoreMatrix ----
    @Test fun scoreMatrix_basic() {
        val img = arrayOf(floatArrayOf(1f, 0f))
        val txt = arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        val m = Scoring.scoreMatrix(img, txt, logitScale = 0.0, logitBias = 0.0)
        assertEquals(1.0f, m.cosine[0][0], 1e-5f)
        assertEquals(0.0f, m.cosine[0][1], 1e-5f)
        assertEquals(0.7310586f, m.confidence[0][0], 1e-5f)
        assertEquals(0.5f, m.confidence[0][1], 1e-5f)
    }

    // ---- aggregateMean ----
    @Test fun aggregateMean_basic() {
        val conf = rows(floatArrayOf(0.3f, 0.7f), floatArrayOf(0.4f, 0.6f))
        val raw = rows(floatArrayOf(0.25f, 0.30f), floatArrayOf(0.27f, 0.28f))
        val r = Scoring.aggregateMean(conf, raw, listOf("driving", "parking"))
        assertEquals(0.35, r.scores[0].confidence, 1e-5)
        assertNull(r.scores[0].peakFrameIndex)
        assertEquals(0.65, r.scores[1].confidence, 1e-5)
        assertEquals("parking", r.bestMatch.label)
    }

    @Test fun aggregateMean_bestMatch_cat() {
        val conf = rows(floatArrayOf(0.8f, 0.1f, 0.1f), floatArrayOf(0.6f, 0.2f, 0.2f))
        val r = Scoring.aggregateMean(conf, conf, listOf("cat", "dog", "bird"))
        assertEquals("cat", r.bestMatch.label)
    }

    // ---- aggregateMax ----
    @Test fun aggregateMax_basic() {
        val conf = rows(floatArrayOf(0.3f, 0.7f), floatArrayOf(0.8f, 0.2f))
        val raw = rows(floatArrayOf(0.25f, 0.30f), floatArrayOf(0.31f, 0.22f))
        val r = Scoring.aggregateMax(conf, raw, listOf("driving", "parking"), doubleArrayOf(0.0, 1.0))
        assertEquals(0.8, r.scores[0].confidence, 1e-5)
        assertEquals(1, r.scores[0].peakFrameIndex)
        assertEquals(1.0, r.scores[0].approxTimestampSeconds!!, 1e-9)
        assertEquals(0.31, r.scores[0].rawSimilarity, 1e-5)
        assertEquals(0.7, r.scores[1].confidence, 1e-5)
        assertEquals(0, r.scores[1].peakFrameIndex)
        assertEquals(0.0, r.scores[1].approxTimestampSeconds!!, 1e-9)
        assertEquals("driving", r.bestMatch.label)
    }

    @Test fun aggregateMax_bestMatch_dog() {
        val conf = rows(floatArrayOf(0.3f, 0.9f, 0.1f), floatArrayOf(0.8f, 0.2f, 0.1f))
        val r = Scoring.aggregateMax(conf, conf, listOf("cat", "dog", "bird"), doubleArrayOf(0.0, 1.0))
        assertEquals("dog", r.bestMatch.label)
    }

    // ---- aggregateTemporal ----
    @Test fun temporal_basic_segment_detection() {
        val conf = col(0.1f, 0.2f, 0.1f, 0.7f, 0.8f, 0.9f, 0.6f, 0.7f, 0.1f, 0.2f)
        val tl = timelineFor(conf, 10.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy"), 0.5, 2.0, 1.0, tl, true)
        val t = r.temporal!!
        assertEquals(10, t.timeline.size)
        assertEquals(1, t.segments.size)
        val seg = t.segments[0]
        assertEquals("sleepy", seg.label)
        assertEquals(3.0, seg.startTime, 1e-9)
        assertEquals(8.0, seg.endTime, 1e-9)
        assertEquals(5.0, seg.duration, 1e-6)
        assertEquals(0.9, seg.peakConfidence, 1e-6)
        assertEquals(5.0, seg.peakTimestamp, 1e-9)
    }

    @Test fun temporal_gap_bridging() {
        val conf = col(0.8f, 0.7f, 0.3f, 0.8f, 0.9f)
        val tl = timelineFor(conf, 5.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy"), 0.5, 2.0, 0.0, tl, true)
        val t = r.temporal!!
        assertEquals(1, t.segments.size)
        assertEquals(0.0, t.segments[0].startTime, 1e-9)
        assertEquals(5.0, t.segments[0].endTime, 1e-9)
    }

    @Test fun temporal_gap_not_bridged() {
        val conf = col(0.8f, 0.7f, 0.3f, 0.8f, 0.9f)
        val tl = timelineFor(conf, 5.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy"), 0.5, 0.0, 0.0, tl, true)
        assertEquals(2, r.temporal!!.segments.size)
    }

    @Test fun temporal_min_duration_filter() {
        val conf = col(0.1f, 0.8f, 0.1f, 0.1f, 0.1f)
        val tl = timelineFor(conf, 5.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy"), 0.5, 0.0, 2.0, tl, true)
        assertEquals(0, r.temporal!!.segments.size)
    }

    @Test fun temporal_stats_with_gap() {
        val conf = col(0.8f, 0.7f, 0.3f, 0.9f)
        val tl = timelineFor(conf, 4.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy"), 0.5, 2.0, 0.0, tl, true)
        val seg = r.temporal!!.segments[0]
        assertEquals(0.8, seg.stats.activeAvg, 1e-6)
        assertEquals(0.675, seg.stats.intervalAvg, 1e-6)
        assertEquals(0.75, seg.stats.coverageRatio, 1e-6)
        assertEquals(3.0, seg.stats.activeDuration, 1e-6)
    }

    @Test fun temporal_best_segment() {
        val conf = rows(floatArrayOf(0.7f, 0.95f), floatArrayOf(0.6f, 0.1f))
        val tl = timelineFor(conf, 2.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("a", "b"), 0.5, 0.0, 0.0, tl, true)
        val best = r.temporal!!.bestSegment
        assertNotNull(best)
        assertEquals("b", best!!.label)
        assertEquals(0.95, best.peakConfidence, 1e-6)
    }

    @Test fun temporal_all_below_threshold() {
        val conf = col(0.1f, 0.2f, 0.3f)
        val tl = timelineFor(conf, 3.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy"), 0.5, 2.0, 1.0, tl, true)
        val t = r.temporal!!
        assertEquals(0, t.segments.size)
        assertNull(t.bestSegment)
        assertEquals(3, t.timeline.size)
        assertEquals(1, t.labelSummaries.size)
        assertEquals(0, t.labelSummaries[0].segmentCount)
    }

    @Test fun temporal_effective_threshold() {
        val conf = col(0.8f, 0.2f)
        val tl = timelineFor(conf, 2.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("x"), 0.6, 2.0, 1.0, tl, false)
        val t = r.temporal!!
        assertEquals(0.6, t.effectiveThreshold, 1e-9)
        assertEquals(false, t.thresholdWasDefaulted)
        assertEquals("absolute", t.thresholdMode)
    }

    @Test fun temporal_all_above_threshold() {
        val conf = col(0.8f, 0.9f, 0.7f)
        val tl = timelineFor(conf, 3.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy"), 0.5, 0.0, 0.0, tl, true)
        val t = r.temporal!!
        assertEquals(1, t.segments.size)
        val seg = t.segments[0]
        assertEquals(1.0, seg.stats.coverageRatio, 1e-9)
        assertEquals(seg.stats.intervalAvg, seg.stats.activeAvg, 1e-9)
    }

    @Test fun temporal_label_summaries() {
        val conf = rows(
            floatArrayOf(0.8f, 0.1f),
            floatArrayOf(0.9f, 0.2f),
            floatArrayOf(0.7f, 0.1f),
        )
        val tl = timelineFor(conf, 3.0)
        val r = Scoring.aggregateTemporal(conf, conf, listOf("sleepy", "awake"), 0.5, 0.0, 0.0, tl, true)
        val summaries = r.temporal!!.labelSummaries.associateBy { it.label }
        val sleepy = summaries["sleepy"]!!
        assertEquals(1, sleepy.segmentCount)
        assertEquals(3.0, sleepy.totalActiveDuration, 1e-6)
        assertEquals(0.9, sleepy.peakConfidence, 1e-6)
        assertEquals(0.8, sleepy.durationWeightedConfidence, 1e-6)
        val awake = summaries["awake"]!!
        assertEquals(0, awake.segmentCount)
        assertEquals(0.0, awake.totalActiveDuration, 1e-9)
    }

    // ---- contrastReduce ----
    @Test fun reduce_mean() {
        assertEquals(0.06, Scoring.contrastReduce(floatArrayOf(0.1f, 0.2f, 0.3f, -0.1f, -0.2f), "mean"), 1e-5)
    }

    @Test fun reduce_top_k_mean_positive() {
        val m = floatArrayOf(0.01f, 0.02f, -0.01f, 0.8f, 0.9f, 0.01f, -0.02f, 0.01f, 0.03f, 0.02f)
        assertEquals(0.9, Scoring.contrastReduce(m, "top_k_mean"), 1e-5)
    }

    @Test fun reduce_top_k_mean_negative() {
        val m = floatArrayOf(0.01f, 0.02f, -0.01f, -0.8f, -0.9f, 0.01f, -0.02f, 0.01f, 0.03f, 0.02f)
        assertEquals(-0.9, Scoring.contrastReduce(m, "top_k_mean"), 1e-5)
    }

    @Test fun reduce_top_k_mean_single() {
        assertEquals(0.5, Scoring.contrastReduce(floatArrayOf(0.5f), "top_k_mean"), 1e-5)
    }

    @Test fun reduce_max_positive() {
        assertEquals(0.5, Scoring.contrastReduce(floatArrayOf(0.1f, -0.3f, 0.5f, -0.2f), "max"), 1e-5)
    }

    @Test fun reduce_max_negative() {
        assertEquals(-0.8, Scoring.contrastReduce(floatArrayOf(0.1f, -0.8f, 0.3f, -0.2f), "max"), 1e-5)
    }

    @Test fun reduce_quantile_positive_tail() {
        val m = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.7f)
        assertTrue(Scoring.contrastReduce(m, "quantile") > 0)
    }

    @Test fun reduce_quantile_negative_tail() {
        val m = floatArrayOf(-0.7f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertTrue(Scoring.contrastReduce(m, "quantile") < 0)
    }

    // ---- aggregateContrast ----
    @Test fun contrast_difference_follows_reduction() {
        val conf = Array(10) { floatArrayOf(0.5f, 0.5f) }
        conf[9] = floatArrayOf(0.9f, 0.1f)
        val r = Scoring.aggregateContrast(conf, conf, listOf("safe", "dangerous"), 1, "max", 0.15)
        val c = r.contrast!!
        assertEquals(0.8, c.difference, 1e-6)
        assertEquals(0.54, c.positive.meanGroupScore, 1e-6)
        assertEquals(0.46, c.negative.meanGroupScore, 1e-6)
        assertEquals("positive", c.verdict)
        assertTrue(Math.abs(c.difference - (0.54 - 0.46)) > 0.5)
    }

    @Test fun contrast_positive_verdict() {
        val conf = rows(
            floatArrayOf(0.8f, 0.7f, 0.2f),
            floatArrayOf(0.9f, 0.8f, 0.1f),
            floatArrayOf(0.7f, 0.6f, 0.3f),
            floatArrayOf(0.8f, 0.7f, 0.2f),
            floatArrayOf(0.9f, 0.8f, 0.1f),
        )
        val r = Scoring.aggregateContrast(conf, conf, listOf("safe", "calm", "dangerous"), 2, "mean", 0.15)
        val c = r.contrast!!
        assertEquals("positive", c.verdict)
        assertTrue(c.difference > 0.15)
        assertEquals(2, c.positive.labels.size)
        assertEquals(1, c.negative.labels.size)
        assertEquals("mean", c.labelPooling)
        assertNotNull(c.dominantLabel)
        assertEquals(3, r.scores.size)
    }

    @Test fun contrast_negative_verdict() {
        val conf = rows(
            floatArrayOf(0.1f, 0.2f, 0.8f, 0.9f),
            floatArrayOf(0.2f, 0.1f, 0.9f, 0.8f),
        )
        val r = Scoring.aggregateContrast(conf, conf, listOf("safe", "calm", "reckless", "texting"), 2, "mean", 0.15)
        val c = r.contrast!!
        assertEquals("negative", c.verdict)
        assertTrue(c.difference < -0.15)
    }

    @Test fun contrast_uncertain_verdict() {
        val conf = rows(floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val r = Scoring.aggregateContrast(conf, conf, listOf("a", "b", "c"), 2, "mean", 0.15)
        val c = r.contrast!!
        assertEquals("uncertain", c.verdict)
        assertTrue(Math.abs(c.difference) <= 0.15)
        assertNull(c.dominantLabel)
    }
}
