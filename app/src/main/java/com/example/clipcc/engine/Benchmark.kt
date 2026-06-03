package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import java.io.File

data class FramePrepResult(val modelId: String, val res: Int, val frames: Int, val decodeMs: Long, val preprocessMs: Long)

data class RunMetadata(
    val thermalStatus: Int, val thermalThrottled: Boolean, val batteryPct: Int,
    val charging: Boolean, val runOrder: Int, val wallClockMs: Long, val media3Version: String,
)

data class TimedRun(
    val modelId: String, val backend: Backend, val effectiveBatch: Int,
    val loadMs: Long, val textMs: Long, val visionMsMedian: Long, val visionMsMin: Long,
    val visionMsMax: Long, val scoringMs: Long, val msPerFrame: Double, val fps: Double,
    val endToEndMsSynthetic: Long, val config: BackendConfig, val meta: RunMetadata,
)

@UnstableApi
class Benchmark(private val context: Context, private val env: OrtEnvironment) {
    /** D5 batch map (CPU_EP only). */
    fun visionBatchFor(modelId: String): Int = when {
        modelId.contains("so400m") -> 4
        modelId.contains("large") -> 8
        else -> 16  // base-256 / base-384
    }
    fun framesFor(modelId: String): Int = if (modelId.contains("so400m")) 4 else 16

    private fun now() = System.nanoTime()
    private fun msSince(t0: Long) = (now() - t0) / 1_000_000L

    private fun meta(order: Int): RunMetadata {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = pm.currentThermalStatus
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return RunMetadata(thermal, thermal >= PowerManager.THERMAL_STATUS_MODERATE, pct, charging,
            order, System.currentTimeMillis(), "1.10.1")
    }

    /** Decode + preprocess ONCE per model (timed); returns cached CHW tensors + prep timing. */
    fun prepFrames(modelDir: String, manifest: ModelBundleManifest, videoPath: String):
            Triple<FramePrepResult, List<Bitmap>, FloatArrayHolder> {
        val n = framesFor(manifest.modelId)
        val tDecode = now()
        val (_, sampled) = FrameSampler(context).sample(videoPath, 1.0, n)
        val decodeMs = msSince(tDecode)
        val bitmaps = sampled.map { it.bitmap }
        val tPre = now()
        val per = 3 * manifest.resolution * manifest.resolution
        val flat = FloatArray(bitmaps.size * per)
        for ((i, bmp) in bitmaps.withIndex()) {
            val one = Preprocess.toCHW(bmp, manifest.resolution)
            for (j in 0 until per) flat[i * per + j] = one.get(j)
        }
        val preMs = msSince(tPre)
        return Triple(FramePrepResult(manifest.modelId, manifest.resolution, bitmaps.size, decodeMs, preMs),
            bitmaps, FloatArrayHolder(flat))
    }

    /** Time one CPU lane: warm-up (discard) + median-of-3 vision; load/text/scoring once. */
    fun timeLane(modelDir: String, manifest: ModelBundleManifest, backend: Backend,
                 prep: FloatArrayHolder, frames: Int, labels: List<String>, runOrder: Int): TimedRun {
        require(backend == Backend.CPU_XNNPACK || backend == Backend.CPU_EP) { "only CPU lanes are timed" }
        val res = manifest.resolution
        val visionBatch = if (backend == Backend.CPU_EP) visionBatchFor(manifest.modelId) else 1
        val per = 3 * res * res
        val cfg = BackendConfig()

        // text (once)
        val tLoadT = now()
        val txt = HfTokenizer.fromJson(File("$modelDir/${manifest.tokenizerFile}").readBytes()).use { tk ->
            val ids = labels.map { tk.encodePadded(it) }
            val flat = LongArray(ids.size * manifest.maxLength)
            for (l in ids.indices) System.arraycopy(ids[l], 0, flat, l * manifest.maxLength, manifest.maxLength)
            OrtTower.open("$modelDir/${manifest.textFile}", env, backend, cfg).use { t ->
                val textBatch = if (backend == Backend.CPU_EP) labels.size else 1
                t.encodeText(flat, labels.size, manifest.maxLength, textBatch)
            }
        }
        val textMs = msSince(tLoadT)

        // vision: load, warm-up, median-of-3 (one session reused)
        val tLoadV = now()
        OrtTower.open("$modelDir/${manifest.visionFile}", env, backend, cfg).use { v ->
            val loadMs = msSince(tLoadV)
            val pix = run {
                val b = java.nio.ByteBuffer.allocateDirect(frames * per * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                b.put(prep.data); (b as java.nio.Buffer).rewind(); b
            }
            v.encodeVision(pix, frames, res, visionBatch)  // warm-up (discarded)
            val times = LongArray(3)
            lateinit var img: Array<FloatArray>
            for (r in 0 until 3) {
                val t0 = now()
                img = v.encodeVision(pix, frames, res, visionBatch)
                times[r] = msSince(t0)
                Thread.sleep(1500)  // cool-down between timed runs
            }
            times.sort()
            val median = times[1]; val mn = times[0]; val mx = times[2]
            val tScore = now()
            Scoring.scoreMatrix(img, txt, manifest.logitScale, manifest.logitBias)
            val scoringMs = msSince(tScore)
            val msPerFrame = median.toDouble() / frames
            val fps = if (median > 0) frames * 1000.0 / median else 0.0
            return TimedRun(manifest.modelId, backend, visionBatch, loadMs, textMs, median, mn, mx,
                scoringMs, msPerFrame, fps, prep.decodeMs + prep.preMs + textMs + median + scoringMs,
                cfg, meta(runOrder))
        }
    }

    /** Write all results as JSON to the app external files dir; return the absolute path. */
    fun writeResults(prep: List<FramePrepResult>, runs: List<TimedRun>,
                     caps: List<BackendCapabilityReport>, fileName: String = "benchmark_result.json"): String {
        fun run(r: TimedRun) = """{"model":"${r.modelId}","backend":"${r.backend}","batch":${r.effectiveBatch},""" +
            """"loadMs":${r.loadMs},"textMs":${r.textMs},"visionMsMedian":${r.visionMsMedian},""" +
            """"visionMsMin":${r.visionMsMin},"visionMsMax":${r.visionMsMax},"scoringMs":${r.scoringMs},""" +
            """"msPerFrame":${String.format(java.util.Locale.US, "%.3f", r.msPerFrame)},"fps":${String.format(java.util.Locale.US, "%.3f", r.fps)},""" +
            """"endToEndMsSynthetic":${r.endToEndMsSynthetic},"intraOpThreads":${r.config.intraOpThreads},""" +
            """"thermal":${r.meta.thermalStatus},"thermalThrottled":${r.meta.thermalThrottled},""" +
            """"batteryPct":${r.meta.batteryPct},"runOrder":${r.meta.runOrder},"media3":"${r.meta.media3Version}"}"""
        fun prep(p: FramePrepResult) = """{"model":"${p.modelId}","res":${p.res},"frames":${p.frames},""" +
            """"decodeMs":${p.decodeMs},"preprocessMs":${p.preprocessMs}}"""
        val json = """{"prep":[${prep.joinToString(",") { prep(it) }}],""" +
            """"runs":[${runs.joinToString(",") { run(it) }}],""" +
            """"capabilities":[${caps.joinToString(",") { it.toJson() }}]}"""
        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, fileName)
        out.writeText(json)
        return out.absolutePath
    }
}

/** Holder so prep timing travels with the flat tensor. */
class FloatArrayHolder(val data: FloatArray) { var decodeMs: Long = 0; var preMs: Long = 0 }
