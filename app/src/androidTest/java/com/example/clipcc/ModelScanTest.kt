package com.example.clipcc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clipcc.data.ModelRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Mirrors ClipCCApp's model scan against the app's external files dir — confirms what the picker lists. */
@RunWith(AndroidJUnit4::class)
class ModelScanTest {
    @Test fun scans_external_models_dir() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models")
        val models = ModelRepository(root).scan()
        println("SCAN root=$root count=${models.size}")
        models.forEach {
            println("  ${it.id} ready=${it.ready} reason=${it.reason} precisions=${it.availablePrecisions}")
        }
        assertTrue("at least one ready model in $root", models.any { it.ready })
    }
}
