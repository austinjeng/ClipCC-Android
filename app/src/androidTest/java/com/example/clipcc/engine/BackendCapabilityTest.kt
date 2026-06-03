package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackendCapabilityTest {
    private val dir = "/data/local/tmp/clipcc_models/siglip2-base-patch16-256"

    @Test fun probe_emits_provider_coverage_for_each_backend() {
        val env = OrtEnvironment.getEnvironment()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dummy = BackendCapability.dummyFrame(256)
        for (b in listOf(Backend.CPU_XNNPACK, Backend.CPU_EP, Backend.NNAPI_DEFAULT, Backend.NNAPI_CPU_DISABLED)) {
            val r = BackendCapability.probeVision(dir, "siglip2-base-patch16-256", 256, b, env, cache,
                BackendCapability.dummyFrame(256))
            println("CAP ${r.toJson()}")
        }
        // CPU_XNNPACK must produce a real coverage histogram with >0 total nodes.
        val xnn = BackendCapability.probeVision(dir, "siglip2-base-patch16-256", 256, Backend.CPU_XNNPACK, env, cache, dummy)
        println("CAP_ASSERT xnn_total=${xnn.totalNodes} providers=${xnn.providerCounts.keys}")
        assertTrue("XNNPACK probe found nodes", xnn.totalNodes > 0)
    }
}
