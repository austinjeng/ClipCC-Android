package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * T6 device gate: a v2 multi-precision bundle resolves towers by precision and actually runs on-device.
 * Proves Manifest v2 + Engine.filesFor wiring with real ONNX inference, and that int8 genuinely executes
 * (differs from fp32) while staying sane (close, not garbage). Bundle: clipcc_models_v2/base-256.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PrecisionEngineTest {
    private val dir = "/data/local/tmp/clipcc_models_v2/siglip2-base-patch16-256"
    private val labels = listOf("Car", "texting while driving", "a dog")

    private fun frame(seed: Int): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256)
            b.setPixel(x, y, Color.rgb((x + seed) % 256, (y * 2 + seed) % 256, (x + y + seed) % 256))
        return b
    }

    @Test fun v2_precision_resolution_runs_on_device() {
        val manifest = ModelBundleManifest.parse(File("$dir/manifest.json").readText())
        assertEquals(listOf(Precision.FP32, Precision.FP16, Precision.INT8), manifest.availablePrecisions)
        val env = OrtEnvironment.getEnvironment()
        val frames = listOf(frame(0), frame(99))

        fun cosineFor(p: Precision): Array<FloatArray> {
            val m = Engine(dir, manifest, env, Backend.CPU_EP, visionBatch = 16, precision = p)
                .scoreFrames(frames, labels)
            assertEquals(frames.size, m.confidence.size)
            for (row in m.confidence) {
                assertEquals(labels.size, row.size)
                for (v in row) assertTrue("finite confidence", v.isFinite())
            }
            return m.cosine
        }

        val fp32 = cosineFor(Precision.FP32)
        val int8 = cosineFor(Precision.INT8)
        var maxDiff = 0f
        for (f in fp32.indices) for (l in fp32[f].indices) maxDiff = maxOf(maxDiff, abs(fp32[f][l] - int8[f][l]))
        assertTrue("int8 actually ran (differs from fp32)", maxDiff > 1e-4f)
        assertTrue("int8 sane vs fp32 (~0.03 expected), got $maxDiff", maxDiff < 0.1f)
        frames.forEach { it.recycle() }
    }
}
