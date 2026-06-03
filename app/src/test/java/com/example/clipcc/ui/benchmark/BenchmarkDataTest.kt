package com.example.clipcc.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkDataTest {
    private val json = BenchmarkDataTest::class.java.classLoader!!
        .getResource("phase2-benchmark-result.json")!!.readText()

    @Test fun parses_cpu_timed_rows_with_coverage() {
        val groups = BenchmarkData.parse(json)
        val base = groups.first { it.modelId == "siglip2-base-patch16-256" }
        val xnn = base.timed.first { it.backend == "CPU_XNNPACK" }
        assertEquals(2372.286, xnn.msPerFrame, 1e-3)
        assertEquals(0.422, xnn.fps, 1e-3)
        assertEquals(11.84, xnn.visionDelegatedPct!!, 1e-2)
    }

    @Test fun nnapi_is_capability_only_not_timed() {
        val groups = BenchmarkData.parse(json)
        val base = groups.first { it.modelId == "siglip2-base-patch16-256" }
        assertTrue(base.timed.none { it.backend.startsWith("NNAPI") })
        val nnapi = base.capabilityOnly.first { it.backend == "NNAPI_DEFAULT" }
        assertEquals(0.0, nnapi.visionDelegatedPct, 1e-6)
        assertTrue(nnapi.experimental)
    }
}
