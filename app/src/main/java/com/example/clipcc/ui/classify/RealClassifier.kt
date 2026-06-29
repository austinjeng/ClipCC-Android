package com.example.clipcc.ui.classify

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.example.clipcc.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
class RealClassifier(
    private val context: Context,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Classifier {

    override suspend fun classify(
        req: ClassifyRequest, onProgress: ProgressSink, isCancelled: () -> Boolean,
    ): RunResult = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val manifest = ModelBundleManifest.parse(java.io.File("${req.modelDir}/manifest.json").readText())
        val backend = req.backend.engine
        val chunkSize = ScoringPolicy.visionChunkFor(req.modelId, backend)
        // Defensive: a requested precision the bundle didn't provision falls back to its default.
        val precision = req.precision.takeIf { it in manifest.availablePrecisions } ?: manifest.defaultPrecision
        val engine = Engine(req.modelDir, manifest, env, backend, visionBatch = chunkSize, precision = precision)

        fun ckCancel() { if (isCancelled()) throw RunCancelledException() }

        onProgress(Stage.LOADING_MODEL, 0, 0); ckCancel()
        onProgress(Stage.ENCODING_TEXT, 0, 0)
        val txt = engine.encodeTextEmbeddings(req.labels); ckCancel()

        val sampler = FrameSampler(context)
        val uri = Uri.parse(req.videoUriString)
        val embeddings = ArrayList<FloatArray>()
        val timestamps = ArrayList<Double>()
        val thumbnails = HashMap<Int, Bitmap>()
        val chunk = ArrayList<Bitmap>(chunkSize)
        var chunkDone = 0
        val chunkTotalGuess = (ScoringPolicy.MAX_FRAMES + chunkSize - 1) / chunkSize

        engine.withVisionEncoder { enc ->
            fun flushChunk() {
                if (chunk.isEmpty()) return
                onProgress(Stage.ENCODING_VISION, chunkDone, chunkTotalGuess)
                for (row in enc.encodeChunk(chunk, isCancelled = isCancelled)) embeddings.add(row)
                chunk.forEach { it.recycle() }
                chunk.clear()
                chunkDone++
            }
            fun decodePass(decodeUri: Uri) {
                onProgress(Stage.DECODING, 0, 0)
                sampler.sample(decodeUri, ScoringPolicy.FPS, ScoringPolicy.MAX_FRAMES) { frame ->
                    thumbnails[frame.index] = thumbnail(frame.bitmap)
                    timestamps.add(frame.timestampSec)
                    chunk.add(frame.bitmap)
                    if (chunk.size == chunkSize) flushChunk()
                    !isCancelled()
                }
            }
            try {
                try {
                    decodePass(uri)
                } catch (c: RunCancelledException) {
                    throw c
                } catch (t: Throwable) {
                    if (timestamps.isNotEmpty()) throw t
                    decodePass(Uri.fromFile(copyToCache(uri)))
                }
                flushChunk()
            } finally {
                chunk.forEach { it.recycle() }   // recycle any bitmaps still buffered on error/cancel
                chunk.clear()
            }
        }
        ckCancel()
        if (embeddings.isEmpty()) error("Couldn't decode any frames from the video")

        onProgress(Stage.AGGREGATING, chunkDone, chunkDone)
        val matrices = Scoring.scoreMatrix(
            embeddings.toTypedArray(), txt, manifest.logitScale, manifest.logitBias)
        val ts = timestamps.toDoubleArray()
        val agg = aggregate(req, matrices, ts)

        RunResult(
            result = agg, thumbnails = thumbnails, timestamps = ts,
            meta = RunMeta(req.modelId, req.backend, ts.size,
                System.currentTimeMillis() - start, manifest.scoreSemantics),
        )
    }

    private fun aggregate(req: ClassifyRequest, m: ScoreMatrices, ts: DoubleArray): AggregationResult =
        when (req.mode) {
            AggMode.MEAN -> Scoring.aggregateMean(m.confidence, m.cosine, req.labels)
            AggMode.MAX -> Scoring.aggregateMax(m.confidence, m.cosine, req.labels, ts)
            AggMode.TEMPORAL -> Scoring.aggregateTemporal(
                m.confidence, m.cosine, req.labels,
                req.temporal.threshold, req.temporal.gap, req.temporal.minDuration,
                FrameTimeline(ts, ScoringPolicy.FPS, videoDuration(ts)),
                req.temporal.thresholdWasDefaulted)
            AggMode.CONTRAST -> Scoring.aggregateContrast(
                m.confidence, m.cosine, req.labels, req.posCount,
                req.contrast.reduce, req.contrast.threshold, req.contrast.thresholdWasDefaulted)
        }

    private fun videoDuration(ts: DoubleArray): Double =
        if (ts.isEmpty()) 0.0 else ts.last() + 1.0 / ScoringPolicy.FPS

    /** ~96px longest-edge thumbnail (small, bounded retention for MAX peaks). */
    private fun thumbnail(src: Bitmap): Bitmap {
        val max = 96
        val scale = max.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    /** Copy a content:// video into app cache so Media3 can decode a seekable local file (SAF fallback). */
    private fun copyToCache(uri: Uri): java.io.File {
        val out = java.io.File(context.cacheDir, "clip_input.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("cannot open $uri")
        return out
    }
}
