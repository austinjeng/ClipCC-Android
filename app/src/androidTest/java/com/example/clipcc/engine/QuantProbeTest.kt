package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Spike B fail-fast: vision-tower latency + EP routing for ONE precision variant.
 * Driven by `-e dir <modelDir>` (must contain vision_model.onnx of the variant) + `-e res` + `-e batch`
 * (CPU_EP) + `-e frames` + `-e tag`. Times XNNPACK(batch=1) and CPU_EP(batch) with warm-up + median-of-3,
 * AND records provider node counts from ORT profiling (detects silent dequant-to-fp32 = no int8 kernels).
 * Pair fp32 vs int8 dirs to read the speedup off the same harness. Latency is input-independent for a
 * fixed-shape ViT, so a dummy pixel buffer is fine.
 */
@RunWith(AndroidJUnit4::class)
class QuantProbeTest {
    @Test fun probe() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val a = InstrumentationRegistry.getArguments()
        val dir = a.getString("dir") ?: error("pass -e dir <modelDir containing vision_model.onnx>")
        val res = (a.getString("res") ?: "256").toInt()
        val frames = (a.getString("frames") ?: "16").toInt()
        val cpuEpBatch = (a.getString("batch") ?: "16").toInt()
        val tag = a.getString("tag") ?: File(dir).name
        val env = OrtEnvironment.getEnvironment()

        val per = 3 * res * res
        val pix = ByteBuffer.allocateDirect(frames * per * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until frames * per) pix.put(0.1f); (pix as Buffer).rewind()

        val rows = ArrayList<String>()
        for ((backend, batch) in listOf(Backend.CPU_XNNPACK to 1, Backend.CPU_EP to cpuEpBatch)) {
            val cap = BackendCapability.probeVision(dir, tag, res, backend, env, ctx.cacheDir,
                BackendCapability.dummyFrame(res))
            OrtTower.open("$dir/vision_model.onnx", env, backend).use { v ->
                (pix as Buffer).rewind(); v.encodeVision(pix, frames, res, batch)   // warm-up
                val t = LongArray(3)
                for (r in 0 until 3) {
                    (pix as Buffer).rewind()
                    val t0 = System.nanoTime()
                    v.encodeVision(pix, frames, res, batch)
                    t[r] = (System.nanoTime() - t0) / 1_000_000L
                    Thread.sleep(1000)
                }
                t.sort()
                val median = t[1]; val mpf = median.toDouble() / frames
                val pc = cap.providerCounts.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
                rows.add("""{"tag":"$tag","backend":"$backend","batch":$batch,"frames":$frames,""" +
                    """"msPerFrame":${String.format(java.util.Locale.US, "%.1f", mpf)},"visionMsMedian":$median,""" +
                    """"totalNodes":${cap.totalNodes},"providerCounts":{$pc}}""")
                println("QUANTPROBE $tag $backend msPerFrame=${String.format("%.1f", mpf)} nodes=${cap.totalNodes} providers=${cap.providerCounts}")
            }
        }
        val out = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "quantprobe_$tag.json")
        out.writeText("[${rows.joinToString(",")}]")
        val b = android.os.Bundle(); b.putString("quantprobe_path", out.absolutePath); inst.sendStatus(0, b)
        assertTrue("two backends timed", rows.size == 2)
    }
}
