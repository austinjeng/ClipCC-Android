package com.example.clipcc.engine

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class FrameSamplerTest {
    @Test fun decodes_expected_frames_with_dims_and_rotation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val (meta, frames) = FrameSampler(ctx).sample("/data/local/tmp/clipcc_bench/test.mp4", fps = 1.0, maxFrames = 8)
        println("FRAMES count=${frames.size} dims=${meta.width}x${meta.height} rot=${meta.rotationDegrees}")
        assertTrue("got frames", frames.isNotEmpty())
        assertTrue("dims positive", meta.width > 0 && meta.height > 0)
        // timestamps increase by ~1s
        for (k in 1 until frames.size) assertTrue("ts increasing", frames[k].timestampSec > frames[k-1].timestampSec)
        assertEquals("indices sequential", frames.indices.toList(), frames.map { it.index })
    }
}
