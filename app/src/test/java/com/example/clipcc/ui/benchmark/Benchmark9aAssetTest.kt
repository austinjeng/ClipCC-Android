package com.example.clipcc.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Benchmark9aAssetTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test fun parse9aAsset_has4ModelsWithBothCpuLanes() {
        val groups = BenchmarkData.parse(res("phase2-benchmark-result-9a.json"))
        assertEquals(4, groups.size)
        groups.forEach { g ->
            val backends = g.timed.map { it.backend }.toSet()
            assertTrue("${g.modelId} missing CPU lanes: $backends",
                backends.containsAll(listOf("CPU_XNNPACK", "CPU_EP")))
        }
    }
}
