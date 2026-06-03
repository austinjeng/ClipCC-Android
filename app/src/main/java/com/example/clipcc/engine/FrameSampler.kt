package com.example.clipcc.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.frame.FrameExtractor
import java.io.File

data class SampledFrame(val bitmap: Bitmap, val timestampSec: Double, val index: Int)
data class VideoMeta(val width: Int, val height: Int, val rotationDegrees: Int, val frameCount: Int)

/**
 * Decodes frames from a real video via Media3 [FrameExtractor] (`@UnstableApi`, 1.10.1).
 * Verified API: `FrameExtractor.Builder(Context, MediaItem).build()`, `getFrame(long): ListenableFuture<Frame>`
 * (position in ms), `Frame.bitmap`/`Frame.presentationTimeMs` public fields, release is `close()`.
 * The extractor applies display rotation, so VideoMeta.rotationDegrees is reported 0. Single-threaded.
 */
@UnstableApi
class FrameSampler(private val context: Context) {

    /** Streaming forward pass: invoke [onFrame] per decoded frame; stop early when it returns false
     *  (cancel) or at EOF. Returns [VideoMeta] (rotation already applied; reported 0). */
    fun sample(uri: Uri, fps: Double, maxFrames: Int, onFrame: (SampledFrame) -> Boolean): VideoMeta {
        val extractor = FrameExtractor.Builder(context, MediaItem.fromUri(uri)).build()
        try {
            var w = 0; var h = 0
            val stepMs = (1000.0 / fps).toLong()
            var lastPtsMs = Long.MIN_VALUE
            var i = 0; var produced = 0
            while (i < maxFrames) {
                val frame = extractor.getFrame(i * stepMs).get() ?: break
                if (frame.presentationTimeMs <= lastPtsMs) break   // seek past EOF clamps PTS
                lastPtsMs = frame.presentationTimeMs
                val bmp = frame.bitmap
                if (produced == 0) { w = bmp.width; h = bmp.height }
                val cont = onFrame(SampledFrame(bmp, frame.presentationTimeMs / 1000.0, produced))
                produced++
                if (!cont) break
                i++
            }
            return VideoMeta(w, h, rotationDegrees = 0, frameCount = produced)
        } finally {
            extractor.close()
        }
    }

    /** Path-based variant used by the Benchmark harness — buffers the streaming pass into a list. */
    fun sample(videoPath: String, fps: Double, maxFrames: Int): Pair<VideoMeta, List<SampledFrame>> {
        val frames = ArrayList<SampledFrame>(maxFrames)
        val meta = sample(Uri.fromFile(File(videoPath)), fps, maxFrames) { frames.add(it); true }
        return meta to frames
    }
}
