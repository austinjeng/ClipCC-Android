package com.example.clipcc.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object Preprocess {
    /** Bitmap -> SigLIP2 tensor: RGB, stretch-to-square via PIL-bilinear, /255, (x-0.5)/0.5, CHW. */
    fun toCHW(bitmap: Bitmap, res: Int): FloatBuffer {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h); bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = FloatArray(w * h * 3)                       // interleaved RGB float [0,255]
        for (i in px.indices) {
            val p = px[i]
            rgb[i * 3] = ((p shr 16) and 0xFF).toFloat()     // R (NOT BGR)
            rgb[i * 3 + 1] = ((p shr 8) and 0xFF).toFloat()  // G
            rgb[i * 3 + 2] = (p and 0xFF).toFloat()           // B
        }
        val resized = Resampler.resizeChannelMajor(rgb, w, h, res, res, 3)  // stretch to res x res
        val buf = ByteBuffer.allocateDirect(3 * res * res * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        // CHW + normalize: (v/255 - 0.5)/0.5 = v/127.5 - 1
        for (c in 0 until 3) for (i in 0 until res * res)
            buf.put(resized[i * 3 + c] / 127.5f - 1f)
        (buf as java.nio.Buffer).rewind(); return buf
    }
}
