package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T6 Phase-0 probe: does so400m FP32 (~4.5 GB: vision 1.71 GB + text 2.83 GB external data) fit on the
 * 8 GB Pixel 9a under the engine's residency pattern (encode text → RELEASE → open vision, so peak ≈ the
 * larger single tower, not the sum)? Surviving = fits → so400m+fp32 can be offered with the RAM disclaimer.
 * OOM is a native crash (uncatchable): a dead instrumentation run / no result = does NOT fit.
 * Bundle staged at /data/local/tmp/clipcc_fp32fit/so400m.
 */
@RunWith(AndroidJUnit4::class)
class Fp32FitTest {
    @Test fun so400m_fp32_fits_sequential_residency() {
        val dir = "/data/local/tmp/clipcc_fp32fit/so400m"
        val env = OrtEnvironment.getEnvironment()
        OrtTower.open("$dir/text_model.onnx", env, Backend.CPU_EP).use { t ->
            t.encodeText(LongArray(64) { 0L }, 1, 64, 1)
        }   // text released here, mirroring Engine ordering
        val survived = OrtTower.open("$dir/vision_model.onnx", env, Backend.CPU_EP).use { v ->
            v.encodeVision(BackendCapability.dummyFrame(384), 1, 384, 1); true
        }
        assertTrue("so400m fp32 ran both towers (sequential residency) without OOM", survived)
    }
}
