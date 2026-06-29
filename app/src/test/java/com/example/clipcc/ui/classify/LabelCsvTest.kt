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

    private class TrackingStream(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    @Test fun read_under_cap_passes_through() {
        val r = LabelCsv.read("a\r\nb\rc\n".byteInputStream())
        assertFalse(r.byteTruncated)
        assertEquals("a\r\nb\rc\n", r.text)
    }
    @Test fun read_does_not_close_stream() {
        val s = TrackingStream("a\nb\n".toByteArray())
        LabelCsv.read(s)
        assertFalse(s.closed)
    }
    @Test fun read_overflow_sets_truncated_and_keeps_whole_rows() {
        val sb = StringBuilder()
        while (sb.length <= LabelCsv.MAX_BYTES + 100) sb.append("abcdefghij\n")
        val r = LabelCsv.read(sb.toString().byteInputStream())
        assertTrue(r.byteTruncated)
        assertTrue(r.text.endsWith("\n"))
        assertTrue(r.text.toByteArray().size <= LabelCsv.MAX_BYTES)
        r.text.split("\n").filter { it.isNotEmpty() }.forEach { assertEquals("abcdefghij", it) }
    }
    @Test fun read_giant_single_row_yields_empty() {
        val sb = StringBuilder()
        repeat(LabelCsv.MAX_BYTES + 10) { sb.append('x') }   // no line break at all
        val r = LabelCsv.read(sb.toString().byteInputStream())
        assertTrue(r.byteTruncated)
        assertEquals("", r.text)
    }
    @Test fun read_overflow_drops_partial_multibyte_without_throwing() {
        val sb = StringBuilder()
        while (sb.toString().toByteArray().size < LabelCsv.MAX_BYTES - 1) sb.append("ab\n")
        sb.append("€€€€")   // 3-byte EUR signs straddle the cap
        val r = LabelCsv.read(sb.toString().byteInputStream())
        assertTrue(r.byteTruncated)
        assertFalse(r.text.contains("€"))  // tail after last newline dropped; no partial char
    }
    @Test(expected = java.nio.charset.CharacterCodingException::class)
    fun read_invalid_utf8_throws() {
        LabelCsv.read(byteArrayOf(0x61, 0xFF.toByte(), 0xFE.toByte()).inputStream())
    }

    @Test fun merge_replace_overwrites() {
        val m = LabelCsv.merge(listOf("old"), listOf("a", "b"), replace = true)
        assertEquals(listOf("a", "b"), m.labels)
        assertEquals(2, m.inserted)
    }
    @Test fun merge_append_skips_dups_including_whitespace_variants() {
        val m = LabelCsv.merge(listOf(" car "), listOf("car", "dog"), replace = false)
        assertEquals(listOf(" car ", "dog"), m.labels)  // existing kept verbatim; "car" skipped
        assertEquals(1, m.inserted)
    }
    @Test fun merge_append_into_empty() {
        val m = LabelCsv.merge(emptyList(), listOf("a"), replace = false)
        assertEquals(listOf("a"), m.labels)
        assertEquals(1, m.inserted)
    }

    @Test fun zeroNotice_truncated_takes_precedence() {
        val n = LabelCsv.zeroNotice(LabelCsv.Read("", true), LabelCsv.Parsed(emptyList(), false, 0))
        assertEquals("No complete labels before the file was truncated", n)
    }
    @Test fun zeroNotice_plain() {
        val n = LabelCsv.zeroNotice(LabelCsv.Read("", false), LabelCsv.Parsed(emptyList(), false, 0))
        assertEquals("No labels found in file", n)
    }
    @Test fun replaceNotice_with_dropped() {
        val n = LabelCsv.replaceNotice(LabelCsv.Read("x", false), LabelCsv.Parsed(listOf("a", "b"), false, 2))
        assertEquals("Loaded 2 labels (2 too long, skipped)", n)
    }
    @Test fun appendNotice_with_dups_and_truncation() {
        val n = LabelCsv.appendNotice(
            LabelCsv.Read("x", true), LabelCsv.Parsed(listOf("a", "b", "c"), false, 0),
            LabelCsv.Merged(listOf("a", "b", "c"), inserted = 1, duplicates = 2))
        assertEquals("Added 1 labels, skipped 2 duplicates (file truncated)", n)
    }
    @Test fun merge_append_caps_at_max_labels() {
        val existing = (1..LabelCsv.MAX_LABELS).map { "l$it" }
        val m = LabelCsv.merge(existing, listOf("new"), replace = false)
        assertEquals(LabelCsv.MAX_LABELS, m.labels.size)  // list did not grow past the cap
        assertEquals(0, m.inserted)
        assertTrue(m.truncated)
    }
    @Test fun merge_append_partial_room_caps_and_counts() {
        val existing = (1 until LabelCsv.MAX_LABELS).map { "l$it" }  // size MAX_LABELS - 1
        val m = LabelCsv.merge(existing, listOf("a", "b"), replace = false)  // room for 1
        assertEquals(LabelCsv.MAX_LABELS, m.labels.size)
        assertEquals(1, m.inserted)
        assertTrue(m.truncated)
    }
    @Test fun appendNotice_list_full() {
        val n = LabelCsv.appendNotice(
            LabelCsv.Read("x", false), LabelCsv.Parsed(listOf("a"), false, 0),
            LabelCsv.Merged(listOf("a"), inserted = 0, truncated = true))
        assertEquals("Label list full — no labels added (file truncated)", n)
    }
    @Test fun appendNotice_all_present() {
        val n = LabelCsv.appendNotice(
            LabelCsv.Read("x", false), LabelCsv.Parsed(listOf("a", "b"), false, 0),
            LabelCsv.Merged(listOf("a", "b"), inserted = 0))
        assertEquals("All 2 labels already present", n)
    }
    @Test fun parse_dedup_is_case_sensitive() {
        assertEquals(listOf("Cat", "cat", "CAT"), LabelCsv.parse("Cat\ncat\nCAT").labels)
    }
    @Test fun merge_append_dedup_is_case_sensitive() {
        val m = LabelCsv.merge(listOf("Cat"), listOf("cat"), replace = false)
        assertEquals(listOf("Cat", "cat"), m.labels)
        assertEquals(1, m.inserted)
    }
}
