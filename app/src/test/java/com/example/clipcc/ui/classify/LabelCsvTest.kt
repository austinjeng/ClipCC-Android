package com.example.clipcc.ui.classify

import org.junit.Assert.*
import org.junit.Test

class LabelCsvTest {
    @Test fun parse_basic_rows() {
        assertEquals(listOf("cat", "dog"), LabelCsv.parse("cat\ndog").labels)
    }
    @Test fun parse_handles_crlf_cr_lf() {
        assertEquals(listOf("a", "b", "c"), LabelCsv.parse("a\r\nb\rc").labels)
    }
    @Test fun parse_strips_bom() {
        // 0xFEFF.toChar() = the UTF-8 BOM; written this way so no invisible char hides in the plan.
        assertEquals(listOf("a"), LabelCsv.parse("${0xFEFF.toChar()}a").labels)
    }
    @Test fun parse_unquotes_single_column_field() {
        assertEquals(listOf("a, b", "c\"d"), LabelCsv.parse("\"a, b\"\n\"c\"\"d\"").labels)
    }
    @Test fun parse_comma_row_is_one_label() {
        assertEquals(listOf("a,b,c"), LabelCsv.parse("a,b,c").labels)
    }
    @Test fun parse_skips_blank_rows() {
        assertEquals(listOf("a", "b"), LabelCsv.parse("a\n\n   \nb").labels)
    }
    @Test fun parse_dedups_within_file_by_normalize() {
        val p = LabelCsv.parse("car\n car \ndog")
        assertEquals(listOf("car", "dog"), p.labels)
    }
    @Test fun parse_drops_overlong_rows() {
        val long = "x".repeat(LabelCsv.MAX_LABEL_CHARS + 1)
        val p = LabelCsv.parse("ok\n$long\nalso")
        assertEquals(listOf("ok", "also"), p.labels)
        assertEquals(1, p.dropped)
    }
    @Test fun parse_truncates_at_max_labels() {
        val text = (1..LabelCsv.MAX_LABELS + 5).joinToString("\n") { "l$it" }
        val p = LabelCsv.parse(text)
        assertEquals(LabelCsv.MAX_LABELS, p.labels.size)
        assertTrue(p.labelTruncated)
    }
    @Test fun parse_empty_is_empty() {
        assertEquals(emptyList<String>(), LabelCsv.parse("").labels)
    }
}
