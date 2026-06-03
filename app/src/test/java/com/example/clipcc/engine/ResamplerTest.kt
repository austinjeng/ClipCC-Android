package com.example.clipcc.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ResamplerTest {
    @Test fun downscale_2to1_averages_like_pil_triangle() {
        // 2x2 single-channel image [[0,1],[1,1]] -> 1x1 should be the antialiased mean = 0.75
        val src = floatArrayOf(0f, 1f, 1f, 1f)
        val out = Resampler.resizeChannelMajor(src, srcW = 2, srcH = 2, dstW = 1, dstH = 1, channels = 1)
        assertEquals(0.75f, out[0], 1e-4f)
    }

    @Test fun identity_when_same_size() {
        val src = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val out = Resampler.resizeChannelMajor(src, 2, 2, 2, 2, 1)
        assertEquals(0.1f, out[0], 1e-4f); assertEquals(0.4f, out[3], 1e-4f)
    }

    /** Asymmetric 4->1 downscale of [0,0,0,4]: PIL antialiased triangle (support=scale=4) gives
     *  ~0.833 (weights .2083/.2917/.2917/.2083); a NO-antialias impl (support=1) would give 0.0.
     *  This is the test that actually guards the prefilter the resampler exists for. */
    @Test fun downscale_4to1_antialiased_not_plain_bilinear() {
        val out = Resampler.resizeChannelMajor(floatArrayOf(0f, 0f, 0f, 4f), 4, 1, 1, 1, 1)
        assertEquals(0.8333f, out[0], 1e-3f)
    }
}
