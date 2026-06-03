package com.example.clipcc

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clipcc.engine.ScoringPolicy
import com.example.clipcc.ui.classify.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ClassifyEndToEndSmokeTest {
    @Test fun real_clip_runs_and_populates_mean_result() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "models/siglip2-base-patch16-256")
        assertTrue("provision base-256 into files/models first", dir.exists())
        val video = File(ctx.filesDir, "test.mp4")
        assertTrue("provision test.mp4 first", video.exists())

        val req = ClassifyRequest(
            modelDir = dir.absolutePath, modelId = "siglip2-base-patch16-256",
            backend = UiBackend.CPU_XNNPACK, videoUriString = android.net.Uri.fromFile(video).toString(),
            labels = ScoringPolicy.DEFAULT_LABELS, posCount = 0, mode = AggMode.MEAN,
            temporal = TemporalOptions(), contrast = ContrastOptions(),
        )
        val result = RealClassifier(ctx).classify(req, onProgress = { _, _, _ -> }, isCancelled = { false })

        assertTrue(result.result.scores.isNotEmpty())
        assertTrue(result.result.bestMatch.confidence in 0.0..1.0)
        assertTrue("thumbnails retained per frame", result.thumbnails.isNotEmpty())
        assertTrue("frames decoded", result.meta.frameCount > 0)
    }
}
