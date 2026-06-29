package com.example.clipcc.ui.classify

/** Pure label validation. `cleaned` is trimmed, blanks removed; for contrast it is posLabels + negLabels
 *  and `posCount` = positive count (the engine's contrast contract). */
data class LabelCheck(val cleaned: List<String>, val posCount: Int, val error: String?)

object LabelValidation {
    /** The one canonicalization rule shared by parse/merge/validate: trim only, case-preserving. */
    fun normalize(s: String): String = s.trim()

    fun validate(positives: List<String>, negatives: List<String>, contrast: Boolean): LabelCheck {
        val pos = positives.map { normalize(it) }.filter { it.isNotEmpty() }
        val neg = negatives.map { normalize(it) }.filter { it.isNotEmpty() }

        if (!contrast) {
            if (pos.isEmpty()) return LabelCheck(emptyList(), 0, "Add at least one label")
            dup(pos)?.let { return LabelCheck(emptyList(), 0, it) }
            return LabelCheck(pos, 0, null)
        }
        if (pos.isEmpty() || neg.isEmpty())
            return LabelCheck(emptyList(), 0, "Contrast needs at least one positive and one negative label")
        dup(pos + neg)?.let { return LabelCheck(emptyList(), 0, it) }
        return LabelCheck(pos + neg, pos.size, null)
    }

    private fun dup(all: List<String>): String? {
        val seen = HashSet<String>()
        for (s in all) if (!seen.add(s)) return "Duplicate label: $s"
        return null
    }
}
