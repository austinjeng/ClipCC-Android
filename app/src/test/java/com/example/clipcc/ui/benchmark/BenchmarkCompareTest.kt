package com.example.clipcc.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkCompareTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun snap(name: String) =
        BenchmarkData.parse(res(name)) to BenchmarkData.parseMeta(res(name))

    private val sevenA get() = snap("phase2-benchmark-result.json")
    private val nineA get() = snap("phase2-benchmark-result-9a.json")

    @Test fun hero_isXnnpack_about23pct_range8to37() {
        val c = BenchmarkCompare.build(sevenA, nineA)
        assertEquals(4, c.heroLaneCount)
        assertEquals(23.0, c.avgPctFaster, 1.5)
        assertEquals(8.0, c.minPctFaster, 1.5)
        assertEquals(37.0, c.maxPctFaster, 1.5)
        c.models.forEach { assertEquals("CPU_XNNPACK", it.headline!!.backend) }
        assertTrue(c.header.contains("Tensor G4"))
        assertTrue(c.header.contains("Tensor G2"))
    }

    @Test fun base256_xnnpack_pctFaster_matchesHandComputed_andBandSeparated() {
        val base = BenchmarkCompare.build(sevenA, nineA).models
            .first { it.modelId == "siglip2-base-patch16-256" }
        val xn = base.lanes.first { it.backend == "CPU_XNNPACK" }
        assertEquals(37.1, xn.pctFaster, 0.5)   // (2372.286-1491.313)/2372.286*100
        assertTrue(xn.protocolMatched)
        assertTrue(xn.bandSeparated)
        assertEquals(7, base.frames7a); assertEquals(16, base.frames9a)
    }

    @Test fun base_cpuEp_notProtocolMatched_excludedFromHeadline() {
        val base = BenchmarkCompare.build(sevenA, nineA).models
            .first { it.modelId == "siglip2-base-patch16-256" }
        assertFalse(base.lanes.first { it.backend == "CPU_EP" }.protocolMatched)  // 7 vs 16
        assertEquals("CPU_XNNPACK", base.headline!!.backend)
    }

    @Test fun so400m_cpuEp_protocolMatched_butXnnpackIsHeadline() {
        val so = BenchmarkCompare.build(sevenA, nineA).models
            .first { it.modelId == "siglip2-so400m-patch14-384" }
        assertTrue(so.lanes.first { it.backend == "CPU_EP" }.protocolMatched)     // 4 == 4
        assertEquals("CPU_XNNPACK", so.headline!!.backend)
    }

    @Test fun failClosed_missingFrameMeta_doesNotMatchCpuEp_butXnnpackStillMatches() {
        val (g7, _) = sevenA; val (g9, _) = nineA
        val emptyMeta = SnapshotMeta(null, null, null, null, emptyMap())
        val base = BenchmarkCompare.build(g7 to emptyMeta, g9 to emptyMeta).models
            .first { it.modelId == "siglip2-base-patch16-256" }
        assertFalse("null==null must not match", base.lanes.first { it.backend == "CPU_EP" }.protocolMatched)
        assertTrue(base.lanes.first { it.backend == "CPU_XNNPACK" }.protocolMatched)
    }

    @Test fun load_ok() {
        val r = BenchmarkCompare.load(
            read7a = { res("phase2-benchmark-result.json") },
            read9a = { res("phase2-benchmark-result-9a.json") })
        assertTrue(r is CompareResult.Ok)
        assertEquals(4, (r as CompareResult.Ok).comparison.heroLaneCount)
    }

    @Test fun load_missing9a_returnsNo9a() {
        val r = BenchmarkCompare.load(read7a = { res("phase2-benchmark-result.json") }, read9a = { null })
        assertEquals(CompareResult.Empty(EmptyReason.NO_9A), r)
    }

    @Test fun load_malformed7a_returnsNo7a() {
        val r = BenchmarkCompare.load(read7a = { "{ not json" },
            read9a = { res("phase2-benchmark-result-9a.json") })
        assertEquals(CompareResult.Empty(EmptyReason.NO_7A), r)
    }

    @Test fun load_noOverlap_returnsNoOverlap() {
        val lonely = """{"prep":[{"model":"other","res":256,"frames":7}],
            "runs":[{"model":"other","backend":"CPU_XNNPACK","loadMs":1,"visionMsMedian":10,
            "visionMsMin":10,"visionMsMax":10,"msPerFrame":10.0,"fps":1.0}],"capabilities":[]}"""
        val r = BenchmarkCompare.load(read7a = { lonely },
            read9a = { res("phase2-benchmark-result-9a.json") })
        assertEquals(CompareResult.Empty(EmptyReason.NO_OVERLAP), r)
    }

    // --- synthetic builders for branch-coverage tests (div0 guard, bandSeparated=false, load symmetry) ---
    private fun timed(backend: String, mpf: Double, min: Double = mpf, max: Double = mpf) =
        TimedRow(backend, 0L, mpf.toLong(), mpf, min, max, if (mpf > 0) 1000.0 / mpf else 0.0, null)
    private fun group(id: String, vararg lanes: TimedRow) = ModelGroup(id, lanes.toList(), emptyList())
    private fun metaOf(frames: Map<String, Int>) = SnapshotMeta(null, null, null, null, frames)

    @Test fun div0_guards_produceZeroNotNaN() {
        val g7 = listOf(group("m", timed("CPU_XNNPACK", 0.0)))  // ms7a==0 -> pctFaster guard
        val g9 = listOf(group("m", timed("CPU_XNNPACK", 0.0)))  // ms9a==0 -> speedup guard
        val meta = metaOf(mapOf("m" to 7))
        val lane = BenchmarkCompare.build(g7 to meta, g9 to meta).models.single().lanes.single()
        assertEquals(0.0, lane.pctFaster, 0.0)
        assertEquals(0.0, lane.speedup, 0.0)
        assertFalse("pctFaster must not be NaN", lane.pctFaster.isNaN())
        assertFalse("speedup must not be NaN", lane.speedup.isNaN())
    }

    @Test fun bandSeparated_falseWhenSpreadsOverlap() {
        // 9a max (120) is NOT < 7a min (100) -> the [min,max] bands overlap -> not band-separated
        val g7 = listOf(group("m", timed("CPU_XNNPACK", 150.0, min = 100.0, max = 200.0)))
        val g9 = listOf(group("m", timed("CPU_XNNPACK", 130.0, min = 90.0, max = 120.0)))
        val meta = metaOf(mapOf("m" to 7))
        val lane = BenchmarkCompare.build(g7 to meta, g9 to meta).models.single().lanes.single()
        assertFalse(lane.bandSeparated)
    }

    @Test fun load_missing7a_returnsNo7a() {
        val r = BenchmarkCompare.load(read7a = { null },
            read9a = { res("phase2-benchmark-result-9a.json") })
        assertEquals(CompareResult.Empty(EmptyReason.NO_7A), r)
    }

    @Test fun load_malformed9a_returnsNo9a() {
        val r = BenchmarkCompare.load(read7a = { res("phase2-benchmark-result.json") },
            read9a = { "{ broken" })
        assertEquals(CompareResult.Empty(EmptyReason.NO_9A), r)
    }
}
