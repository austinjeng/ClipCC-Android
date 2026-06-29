package com.example.clipcc.ui.classify

/** Pure CSV/text -> labels: each row is exactly one label. Android-free so the whole import path
 *  (bounded read, strict decode, parse, merge, notices) is JVM-unit-testable. */
object LabelCsv {
    const val MAX_BYTES = 256 * 1024
    const val MAX_LABELS = 1000
    const val MAX_LABEL_CHARS = 256

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
            if (!seen.add(LabelValidation.normalize(label))) continue
            if (out.size >= MAX_LABELS) { labelTruncated = true; break }
            out.add(label)
        }
        return Parsed(out, labelTruncated, dropped)
    }

    /** Strip one surrounding pair of double quotes and unescape "" -> " (RFC-4180 single column). */
    private fun unquote(s: String): String =
        if (s.length >= 2 && s.first() == '"' && s.last() == '"')
            s.substring(1, s.length - 1).replace("\"\"", "\"")
        else s
}
