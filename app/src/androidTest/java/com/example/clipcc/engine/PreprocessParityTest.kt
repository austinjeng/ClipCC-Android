package com.example.clipcc.engine

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PreprocessParityTest {
    @Test fun chw_matches_pil_golden_within_tolerance() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val g = JSONObject(ctx.assets.open("fixtures/preprocess_golden.json").bufferedReader().readText())
        val shape = g.getJSONArray("shape"); val res = shape.getInt(2)
        val data = g.getJSONArray("data")
        val frames = shape.getInt(0); val per = 3 * res * res
        // frame files were generated as frame_000.png, frame_001.png ...
        var maxAbs = 0f
        for (f in 0 until frames) {
            val bmp = ctx.assets.open("fixtures/frame_%03d.png".format(f)).use { BitmapFactory.decodeStream(it) }
            val buf = Preprocess.toCHW(bmp, res)
            for (i in 0 until per) maxAbs = maxOf(maxAbs, abs(buf.get(i) - data.getDouble(f * per + i).toFloat()))
        }
        println("PREPROCESS max_abs_diff=$maxAbs")
        assertTrue("max abs diff $maxAbs <= 0.016", maxAbs <= 0.016f)
    }
}
