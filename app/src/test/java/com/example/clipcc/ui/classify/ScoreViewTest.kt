package com.example.clipcc.ui.classify

import com.example.clipcc.engine.LabelSummary
import com.example.clipcc.engine.ScoreItem
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ScoreViewTest {
    private fun s(label: String, conf: Double) = ScoreItem(label, conf, 0.0)

    @Test fun ranked_sorts_desc_and_is_stable_on_ties() {
        val r = ScoreView.ranked(listOf(s("a", 0.2), s("b", 0.9), s("c", 0.9), s("d", 0.5)))
        assertEquals(listOf("b", "c", "d", "a"), r.map { it.label })   // b before c (stable tie)
    }
    @Test fun ranked_returns_all_items_caller_caps() {
        val many = (1..1000).map { s("l$it", it / 1000.0) }
        assertEquals(1000, ScoreView.ranked(many).size)                          // ranked returns all
        assertEquals(50, ScoreView.ranked(many).take(ScoreView.MAX_ROWS).size)   // caller bounds it
    }
    @Test fun pct_signedCos_secs_use_us_locale() {
        val def = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)   // comma decimal separator
            assertEquals("87.3%", ScoreView.pct(0.873))
            assertEquals("100.0%", ScoreView.pct(1.0))
            assertEquals("0.0%", ScoreView.pct(0.0))
            assertEquals("+0.420", ScoreView.signedCos(0.42))
            assertEquals("-0.050", ScoreView.signedCos(-0.05))
            assertEquals("3.2 s", ScoreView.secs(3.2))
        } finally { Locale.setDefault(def) }
    }
    @Test fun topSummaries_filters_inactive_and_caps_by_dwc() {
        val ls = listOf(
            LabelSummary("a", 2, 1.0, 1.0, 0.9, 0.8),
            LabelSummary("z", 0, 0.0, 0.0, 0.0, 0.0),   // inactive (segmentCount 0)
            LabelSummary("b", 1, 1.0, 1.0, 0.9, 0.95),
        )
        assertEquals(listOf("b", "a"), ScoreView.topSummaries(ls, 10).map { it.label })
        assertEquals(1, ScoreView.topSummaries(ls, 1).size)
    }
    @Test fun topActiveLabels_empty_when_no_segments() {
        val ls = listOf(LabelSummary("a", 0, 0.0, 0.0, 0.0, 0.0))
        assertTrue(ScoreView.topActiveLabels(ls, 6).isEmpty())
    }
    @Test fun visibleModes_hides_contrast_until_unlocked_or_current() {
        assertFalse(AggMode.CONTRAST in ScoreView.visibleModes(false, AggMode.MEAN))
        assertTrue(AggMode.CONTRAST in ScoreView.visibleModes(true, AggMode.MEAN))
        assertTrue(AggMode.CONTRAST in ScoreView.visibleModes(false, AggMode.CONTRAST))
    }
    @Test fun constants_are_fixed() {
        assertEquals(5, ScoreView.COLLAPSED)
        assertEquals(50, ScoreView.MAX_ROWS)
        assertEquals(6, ScoreView.TIMELINE_SERIES)
        assertEquals(20, ScoreView.SUMMARY_ROWS)
        assertEquals(50, ScoreView.SEGMENT_ROWS)
    }
}
