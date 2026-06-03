package com.example.clipcc.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** PIL/Pillow convolution bilinear (triangle filter), separable, half-pixel centers,
 *  antialiased on downscale. Input/output are HWC-interleaved float planes (channels last). */
object Resampler {
    private fun triangle(x: Float): Float { val a = if (x < 0) -x else x; return if (a < 1f) 1f - a else 0f }

    private fun axisWeights(srcN: Int, dstN: Int): Pair<IntArray, Array<FloatArray>> {
        val scale = srcN.toFloat() / dstN
        val support = if (scale > 1f) scale else 1f           // triangle support; widen on downscale
        val starts = IntArray(dstN); val weights = Array(dstN) { FloatArray(0) }
        for (o in 0 until dstN) {
            val center = (o + 0.5f) * scale - 0.5f            // half-pixel center
            val lo = max(0, floor(center - support).toInt())
            val hi = minOf(srcN - 1, ceil(center + support).toInt())
            val w = FloatArray(hi - lo + 1); var sum = 0f
            for (s in lo..hi) { val ww = triangle((s - center) / support); w[s - lo] = ww; sum += ww }
            if (sum > 0f) for (k in w.indices) w[k] /= sum
            starts[o] = lo; weights[o] = w
        }
        return starts to weights
    }

    /** resize one interleaved float image [srcH*srcW*channels] -> [dstH*dstW*channels]. */
    fun resizeChannelMajor(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, channels: Int): FloatArray {
        val (xs, xw) = axisWeights(srcW, dstW)
        val (ys, yw) = axisWeights(srcH, dstH)
        // horizontal pass: srcH x dstW
        val tmp = FloatArray(srcH * dstW * channels)
        for (y in 0 until srcH) for (ox in 0 until dstW) {
            val w = xw[ox]; val s0 = xs[ox]
            for (c in 0 until channels) {
                var acc = 0f
                for (k in w.indices) acc += w[k] * src[(y * srcW + (s0 + k)) * channels + c]
                tmp[(y * dstW + ox) * channels + c] = acc
            }
        }
        // vertical pass: dstH x dstW
        val out = FloatArray(dstH * dstW * channels)
        for (oy in 0 until dstH) for (x in 0 until dstW) {
            val w = yw[oy]; val s0 = ys[oy]
            for (c in 0 until channels) {
                var acc = 0f
                for (k in w.indices) acc += w[k] * tmp[((s0 + k) * dstW + x) * channels + c]
                out[(oy * dstW + x) * channels + c] = acc
            }
        }
        return out
    }
}
