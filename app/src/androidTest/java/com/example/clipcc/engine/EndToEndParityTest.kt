package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EndToEndParityTest {
    private val dir = "/data/local/tmp/clipcc_models/siglip2-base-patch16-256"

    private fun mat(a: JSONArray): Array<FloatArray> =
        Array(a.length()) { i ->
            val row = a.getJSONArray(i)
            FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
        }

    private fun argmax(v: FloatArray): Int { var bi = 0; for (i in v.indices) if (v[i] > v[bi]) bi = i; return bi }

    @Test fun scores_match_python_golden_within_tolerance() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val g = JSONObject(ctx.assets.open("fixtures/scores_golden.json").bufferedReader().readText())
        val labels = g.getJSONArray("labels").let { la -> List(la.length()) { la.getString(it) } }
        val frameNames = g.getJSONArray("frames").let { fa -> List(fa.length()) { fa.getString(it) } }
        val gCos = mat(g.getJSONArray("cosine"))
        val gConf = mat(g.getJSONArray("confidence"))

        val bitmaps = frameNames.map { name ->
            ctx.assets.open("fixtures/$name").use { BitmapFactory.decodeStream(it) }
        }
        val manifest = ModelBundleManifest.parse(File("$dir/manifest.json").readText())
        val sm = Engine(dir, manifest, OrtEnvironment.getEnvironment()).scoreFrames(bitmaps, labels)

        assertEquals("frame count", gCos.size, sm.cosine.size)
        assertEquals("label count", labels.size, sm.cosine[0].size)

        var cosMax = 0f; var confMax = 0f; var flips = 0
        for (f in gCos.indices) {
            for (l in labels.indices) {
                cosMax = maxOf(cosMax, abs(sm.cosine[f][l] - gCos[f][l]))
                confMax = maxOf(confMax, abs(sm.confidence[f][l] - gConf[f][l]))
            }
            if (argmax(sm.confidence[f]) != argmax(gConf[f])) flips++
        }
        println("E2E cosine_max=$cosMax confidence_max=$confMax best_match_flips=$flips")
        assertTrue("cosine max abs $cosMax <= 0.01", cosMax <= 0.01f)
        assertTrue("confidence max abs $confMax <= 0.02", confMax <= 0.02f)
        assertEquals("best-match label flips", 0, flips)
    }

    @Test fun scores_match_golden_on_cpu_ep() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val g = JSONObject(ctx.assets.open("fixtures/scores_golden.json").bufferedReader().readText())
        val labels = g.getJSONArray("labels").let { la -> List(la.length()) { la.getString(it) } }
        val frameNames = g.getJSONArray("frames").let { fa -> List(fa.length()) { fa.getString(it) } }
        val gCos = mat(g.getJSONArray("cosine"))
        val bitmaps = frameNames.map { n -> ctx.assets.open("fixtures/$n").use { BitmapFactory.decodeStream(it) } }
        val manifest = ModelBundleManifest.parse(File("$dir/manifest.json").readText())
        val sm = Engine(dir, manifest, OrtEnvironment.getEnvironment(),
            backend = Backend.CPU_EP, visionBatch = 16).scoreFrames(bitmaps, labels)
        var cosMax = 0f
        for (f in gCos.indices) for (l in labels.indices) cosMax = maxOf(cosMax, kotlin.math.abs(sm.cosine[f][l] - gCos[f][l]))
        println("E2E_CPU_EP cosine_max=$cosMax")
        assertTrue("cpu_ep cosine max $cosMax <= 0.01", cosMax <= 0.01f)
    }
}
