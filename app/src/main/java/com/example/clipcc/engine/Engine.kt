package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Thrown by the chunked encode path when the cooperative cancel flag is observed. */
class RunCancelledException : RuntimeException("run cancelled")

/**
 * Headless engine: (frames + labels) -> [F x L] cosine/confidence matrices on a chosen [backend].
 * Memory order (Spike 0d): encode text, RELEASE the text session, THEN open vision.
 * Under XNNPACK both towers run per-item (batch=1, batch-collapse); under CPU_EP they batch.
 */
class Engine(
    private val modelDir: String,
    private val manifest: ModelBundleManifest,
    private val env: OrtEnvironment,
    private val backend: Backend = Backend.CPU_XNNPACK,
    private val visionBatch: Int = 1,
    private val config: BackendConfig = BackendConfig(),
) {
    private fun itemBatch() = if (backend == Backend.CPU_EP) Int.MAX_VALUE else 1

    fun scoreFrames(bitmaps: List<Bitmap>, labels: List<String>): ScoreMatrices {
        val effVisionBatch = if (backend == Backend.CPU_EP) visionBatch else 1
        val txt: Array<FloatArray> =
            HfTokenizer.fromJson(File("$modelDir/${manifest.tokenizerFile}").readBytes()).use { tk ->
                val ids = labels.map { tk.encodePadded(it) }
                OrtTower.open("$modelDir/${manifest.textFile}", env, backend, config).use { t ->
                    t.encodeText(flatten(ids), labels.size, manifest.maxLength, minOf(itemBatch(), labels.size))
                }
            }
        return OrtTower.open("$modelDir/${manifest.visionFile}", env, backend, config).use { v ->
            val pix = packFrames(bitmaps, manifest.resolution)
            val img = v.encodeVision(pix, bitmaps.size, manifest.resolution, effVisionBatch)
            Scoring.scoreMatrix(img, txt, manifest.logitScale, manifest.logitBias)
        }
    }

    private fun flatten(ids: List<LongArray>): LongArray {
        val maxLen = manifest.maxLength
        val out = LongArray(ids.size * maxLen)
        for (l in ids.indices) { require(ids[l].size == maxLen); System.arraycopy(ids[l], 0, out, l * maxLen, maxLen) }
        return out
    }

    private fun packFrames(bitmaps: List<Bitmap>, res: Int): FloatBuffer {
        val per = 3 * res * res
        val buf = ByteBuffer.allocateDirect(bitmaps.size * per * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (bmp in bitmaps) { val one = Preprocess.toCHW(bmp, res); for (i in 0 until per) buf.put(one.get(i)) }
        (buf as Buffer).rewind(); return buf
    }

    /** Open the text tower, encode [labels] -> L2-normalized embeddings, release the session.
     *  Mirrors scoreFrames' text-first/release ordering so the vision session is the only large one. */
    fun encodeTextEmbeddings(labels: List<String>): Array<FloatArray> =
        HfTokenizer.fromJson(File("$modelDir/${manifest.tokenizerFile}").readBytes()).use { tk ->
            val ids = labels.map { tk.encodePadded(it) }
            OrtTower.open("$modelDir/${manifest.textFile}", env, backend, config).use { t ->
                t.encodeText(flatten(ids), labels.size, manifest.maxLength, minOf(itemBatch(), labels.size))
            }
        }

    /** A chunk encoder bound to an open vision session. Encode one frame chunk -> [chunk][D] embeddings. */
    inner class VisionEncoder internal constructor(private val tower: OrtTower) {
        private val effBatch = if (backend == Backend.CPU_EP) visionBatch else 1
        fun encodeChunk(
            bitmaps: List<Bitmap>, onItem: ((Int) -> Unit)? = null, isCancelled: (() -> Boolean)? = null,
        ): Array<FloatArray> {
            val pix = packFrames(bitmaps, manifest.resolution)
            return tower.encodeVision(pix, bitmaps.size, manifest.resolution,
                minOf(effBatch, bitmaps.size), onItem, isCancelled)
        }
    }

    /** Open the vision tower for the duration of [block], releasing it afterward. */
    fun <R> withVisionEncoder(block: (VisionEncoder) -> R): R =
        OrtTower.open("$modelDir/${manifest.visionFile}", env, backend, config).use { v -> block(VisionEncoder(v)) }
}
