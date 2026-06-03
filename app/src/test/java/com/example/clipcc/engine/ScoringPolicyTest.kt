package com.example.clipcc.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoringPolicyTest {
    @Test fun constants_match_python() {
        assertEquals(0.5, ScoringPolicy.THRESHOLD, 0.0)
        assertEquals("absolute", ScoringPolicy.THRESHOLD_MODE)
        assertEquals(2.0, ScoringPolicy.GAP_TOLERANCE, 0.0)
        assertEquals(1.0, ScoringPolicy.MIN_DURATION, 0.0)
        assertEquals(0.15, ScoringPolicy.CONTRAST_THRESHOLD, 0.0)
        assertEquals("mean", ScoringPolicy.CONTRAST_REDUCE)
        assertEquals(listOf("mean", "top_k_mean", "max", "quantile"), ScoringPolicy.CONTRAST_REDUCE_MODES)
        assertEquals("siglip2_pairwise_sigmoid", ScoringPolicy.SCORE_SEMANTICS)
        assertEquals(1.0, ScoringPolicy.FPS, 0.0)
        assertEquals(300, ScoringPolicy.MAX_FRAMES)
        assertEquals(
            listOf("texting while driving", "sleeping while driving", "eating while driving"),
            ScoringPolicy.DEFAULT_LABELS,
        )
    }

    @Test fun vision_chunk_matches_phase2_batches() {
        assertEquals(16, ScoringPolicy.visionChunkFor("siglip2-base-patch16-256", Backend.CPU_EP))
        assertEquals(16, ScoringPolicy.visionChunkFor("siglip2-base-patch16-384", Backend.CPU_EP))
        assertEquals(8, ScoringPolicy.visionChunkFor("siglip2-large-patch16-384", Backend.CPU_EP))
        assertEquals(4, ScoringPolicy.visionChunkFor("siglip2-so400m-patch14-384", Backend.CPU_EP))
        assertEquals(16, ScoringPolicy.visionChunkFor("siglip2-so400m-patch14-384", Backend.CPU_XNNPACK))
    }
}
