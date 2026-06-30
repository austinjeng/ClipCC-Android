package com.example.clipcc.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkDataMetaTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test fun meta9a_hasProvenanceAndFrames() {
        val m = BenchmarkData.parseMeta(res("phase2-benchmark-result-9a.json"))
        assertEquals("Pixel 9a", m.deviceModel)
        assertEquals("Google Tensor G4", m.soc)
        assertEquals("1.26.0", m.ort)
        assertEquals(16, m.framesByModel["siglip2-base-patch16-256"])
        assertEquals(4, m.framesByModel["siglip2-so400m-patch14-384"])
    }

    @Test fun meta7a_noDeviceOrOrt_butHasFrames() {
        val m = BenchmarkData.parseMeta(res("phase2-benchmark-result.json"))
        assertNull(m.deviceModel)
        assertNull(m.ort)
        assertEquals(7, m.framesByModel["siglip2-base-patch16-256"])
        assertEquals(4, m.framesByModel["siglip2-so400m-patch14-384"])
    }

    @Test fun timedRow_perFrameSpread_bracketsMedian() {
        val g = BenchmarkData.parse(res("phase2-benchmark-result-9a.json"))
        val ep = g.first { it.modelId == "siglip2-so400m-patch14-384" }
            .timed.first { it.backend == "CPU_EP" }   // 9a so400m EP: noisy lane, 14450-17096 per frame
        assertTrue(ep.msPerFrameMin <= ep.msPerFrame)
        assertTrue(ep.msPerFrameMax >= ep.msPerFrame)
        assertTrue(ep.msPerFrameMin < ep.msPerFrameMax)
    }
}
