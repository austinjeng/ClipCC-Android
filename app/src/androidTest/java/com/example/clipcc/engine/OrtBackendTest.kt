package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OrtBackendTest {
    private val dir = "/data/local/tmp/clipcc_models/siglip2-base-patch16-256"
    private val res = 256
    private val dim = 768

    private fun twoFrames(): FloatBuffer {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val per = 3 * res * res
        val buf = ByteBuffer.allocateDirect(2 * per * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (name in listOf("frame_000.png", "frame_001.png")) {
            val bmp = ctx.assets.open("fixtures/$name").use { BitmapFactory.decodeStream(it) }
            val one = Preprocess.toCHW(bmp, res)
            for (i in 0 until per) buf.put(one.get(i))
        }
        (buf as Buffer).rewind(); return buf
    }

    @Test fun cpu_lanes_open_and_batched_equals_perframe() {
        val env = OrtEnvironment.getEnvironment()
        // XNNPACK per-frame (batch=1)
        val xnn = OrtTower.open("$dir/vision_model.onnx", env, Backend.CPU_XNNPACK).use {
            it.encodeVision(twoFrames(), 2, res, 1)
        }
        // CPU EP batched (batch=2 in one session.run)
        val cpu = OrtTower.open("$dir/vision_model.onnx", env, Backend.CPU_EP).use {
            it.encodeVision(twoFrames(), 2, res, 2)
        }
        assertEquals(2, xnn.size); assertEquals(dim, xnn[0].size)
        assertEquals(2, cpu.size); assertEquals(dim, cpu[0].size)
        var maxAbs = 0f
        for (f in 0 until 2) for (i in 0 until dim) maxAbs = maxOf(maxAbs, abs(xnn[f][i] - cpu[f][i]))
        println("BACKEND xnnpack_vs_cpuep max_abs=$maxAbs")
        assertTrue("XNNPACK per-frame vs CPU_EP batched agree ($maxAbs)", maxAbs <= 1e-2f)
    }

    @Test fun nnapi_attempt_records_outcome_without_crashing() {
        val env = OrtEnvironment.getEnvironment()
        var got: OpenOutcome? = null
        try {
            OrtTower.open("$dir/vision_model.onnx", env, Backend.NNAPI_DEFAULT, outcome = { got = it }).use {
                it.encodeVision(twoFrames(), 1, res, 1)  // runs on whatever applied (likely CPU fallback)
            }
        } catch (t: Throwable) {
            // session-create failure is an acceptable, recorded outcome
            assertTrue("outcome captured on throw", got != null)
        }
        println("BACKEND nnapi_default outcome=$got")
        assertTrue("an outcome was recorded", got != null)
    }

    @Test fun text_lane_shapes_and_consistency() {
        val env = OrtEnvironment.getEnvironment()
        val ids = HfTokenizer.fromJson(java.io.File("$dir/tokenizer.json").readBytes()).use {
            it.encodePadded("a photo of a car")
        }
        val xnn = OrtTower.open("$dir/text_model.onnx", env, Backend.CPU_XNNPACK).use {
            it.encodeText(ids, 1, HfTokenizer.MAX_LEN, 1)
        }
        val cpu = OrtTower.open("$dir/text_model.onnx", env, Backend.CPU_EP).use {
            it.encodeText(ids, 1, HfTokenizer.MAX_LEN, 1)
        }
        assertEquals(1, xnn.size); assertEquals(dim, xnn[0].size)
        var m = 0f; for (i in 0 until dim) m = maxOf(m, abs(xnn[0][i] - cpu[0][i]))
        println("BACKEND text_xnn_vs_cpu max_abs=$m")
        assertTrue("text lanes agree ($m)", m <= 1e-2f)
    }
}
