package com.example.clipcc.ui.classify

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Pure CSV/text -> labels: each row is exactly one label. Android-free so the whole import path
 *  (bounded read, strict decode, parse, merge, notices) is JVM-unit-testable. */
object LabelCsv {
    const val MAX_BYTES = 256 * 1024
    const val MAX_LABELS = 1000
    const val MAX_LABEL_CHARS = 256

    data class Read(val text: String, val byteTruncated: Boolean)

    /** Reads up to MAX_BYTES (+1 sentinel byte to detect overflow). On overflow keeps only whole
     *  rows (trims to the last line-break byte) so the cut never lands mid-row/quote/codepoint.
     *  Decodes strict UTF-8 (throws on malformed input). Does NOT close [input] — caller uses `.use`. */
    fun read(input: InputStream): Read {
        val buf = ByteArray(MAX_BYTES + 1)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        val byteTruncated = n > MAX_BYTES
        var end = if (byteTruncated) MAX_BYTES else n
        if (byteTruncated) {
            var lastBreak = -1
            for (i in 0 until end) {
                val b = buf[i]
                if (b == '\n'.code.toByte() || b == '\r'.code.toByte()) lastBreak = i
            }
            end = lastBreak + 1   // 0 when there is no line break in range -> empty text
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return Read(decoder.decode(ByteBuffer.wrap(buf, 0, end)).toString(), byteTruncated)
    }

    data class Parsed(val labels: List<String>, val labelTruncated: Boolean, val dropped: Int)

    fun parse(text: String): Parsed {
        val rows = text.removePrefix("${0xFEFF.toChar()}").split(Regex("\r\n|\r|\n"))  // strip UTF-8 BOM
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        var dropped = 0
        var labelTruncated = false
        for (row in rows) {
            val label = unquote(LabelValidation.normalize(row))
            if (label.isEmpty()) continue
            if (label.length > MAX_LABEL_CHARS) { dropped++; continue }
            // normalize again: unquote() above can reintroduce surrounding whitespace, so the dedup
            // key must be re-trimmed (the stored label keeps its post-unquote form).
            if (!seen.add(LabelValidation.normalize(label))) continue
            if (out.size >= MAX_LABELS) { labelTruncated = true; break }
            out.add(label)
        }
        return Parsed(out, labelTruncated, dropped)
    }

    data class Merged(
        val labels: List<String>,
        val inserted: Int,
        val duplicates: Int = 0,
        /** True when novel labels were dropped to keep the list at MAX_LABELS. */
        val truncated: Boolean = false,
    )

    fun merge(existing: List<String>, parsed: List<String>, replace: Boolean): Merged {
        if (replace) return Merged(parsed, parsed.size)   // parsed is already ≤ MAX_LABELS (parse caps it)
        val have = existing.mapTo(HashSet()) { LabelValidation.normalize(it) }
        val novel = parsed.filter { have.add(LabelValidation.normalize(it)) }
        val room = (MAX_LABELS - existing.size).coerceAtLeast(0)
        val added = novel.take(room)
        return Merged(existing + added, added.size,
            duplicates = parsed.size - novel.size, truncated = added.size < novel.size)
    }

    private fun truncated(read: Read, parsed: Parsed) = read.byteTruncated || parsed.labelTruncated

    private fun suffix(truncated: Boolean, dropped: Int): String =
        (if (truncated) " (file truncated)" else "") +
        (if (dropped > 0) " ($dropped too long, skipped)" else "")

    fun zeroNotice(read: Read, parsed: Parsed): String =
        if (truncated(read, parsed)) "No complete labels before the file was truncated"
        else "No labels found in file"

    fun replaceNotice(read: Read, parsed: Parsed): String =
        "Loaded ${parsed.labels.size} labels" + suffix(truncated(read, parsed), parsed.dropped)

    fun appendNotice(read: Read, parsed: Parsed, merged: Merged): String {
        val head = when {
            merged.inserted > 0 ->
                "Added ${merged.inserted} labels" +
                    (if (merged.duplicates > 0) ", skipped ${merged.duplicates} duplicates" else "")
            merged.truncated -> "Label list full — no labels added"
            else -> "All ${parsed.labels.size} labels already present"
        }
        return head + suffix(truncated(read, parsed) || merged.truncated, parsed.dropped)
    }

    /** Strip one surrounding pair of double quotes and unescape "" -> " (RFC-4180 single column). */
    private fun unquote(s: String): String =
        if (s.length >= 2 && s.first() == '"' && s.last() == '"')
            s.substring(1, s.length - 1).replace("\"\"", "\"")
        else s
}
