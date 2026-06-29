# CSV Label Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user populate a Classify label list from a picked CSV/text file, one label per row, with an informed Replace/Append choice.

**Architecture:** All logic lives in one Android-free, JVM-tested object `LabelCsv` (`read` a bounded `InputStream`, `parse` text → labels, `merge` into a list, build notice strings). The ViewModel gains a tested `setLabelList(target, list)` router. `SetupCard` is thin glue: pick a file, read+parse off the main thread, show a counts dialog, commit via the ViewModel.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `androidx.activity.compose` Activity Result APIs (already used for the video picker), kotlinx-coroutines, `java.nio` UTF-8 decoder. **No new dependencies.**

## Global Constraints

- **Labels are case-sensitive — NEVER lowercase.** `LabelValidation.normalize(s) = s.trim()` (trim only). Lowercasing breaks tokenizer parity (load-bearing).
- Caps (constants in `LabelCsv`): `MAX_BYTES = 256 * 1024`, `MAX_LABELS = 1000`, `MAX_LABEL_CHARS = 256`.
- Reader does **strict UTF-8** decode (`CodingErrorAction.REPORT`) — malformed/binary input throws, import rejected, list unchanged.
- `LabelCsv.read` does **NOT** close its `InputStream`; the caller wraps it in `.use`.
- An empty parse (`labels.isEmpty()`) **never** modifies a list (no Replace offered).
- Tests are **pure-JVM only** (`:app:testDebugUnitTest`). No device/instrumented test for this feature.
- minSdk 24.
- JVM test command (JBR 21):
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest`
  Filter a class with `--tests "com.example.clipcc.ui.classify.LabelCsvTest"`.
- Branch: `feat/csv-label-import` (already created off `main`).

---

### Task 1: `LabelValidation.normalize` (shared canonicalization)

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/LabelValidation.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/classify/LabelValidationTest.kt`

**Interfaces:**
- Produces: `LabelValidation.normalize(s: String): String` — the single trim rule reused by parse/merge/validate.

- [ ] **Step 1: Write the failing test** — append to `LabelValidationTest.kt`:

```kotlin
    @Test fun normalize_trims_preserving_case() {
        assertEquals("Car", LabelValidation.normalize("  Car "))
        assertEquals("a b", LabelValidation.normalize("a b"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelValidationTest"`
Expected: FAIL — `Unresolved reference: normalize`.

- [ ] **Step 3: Add `normalize` and route `validate` through it.** In `LabelValidation.kt`, add the function and replace the two `.trim()` mappings:

```kotlin
object LabelValidation {
    /** The one canonicalization rule shared by parse/merge/validate: trim only, case-preserving. */
    fun normalize(s: String): String = s.trim()

    fun validate(positives: List<String>, negatives: List<String>, contrast: Boolean): LabelCheck {
        val pos = positives.map { normalize(it) }.filter { it.isNotEmpty() }
        val neg = negatives.map { normalize(it) }.filter { it.isNotEmpty() }
        // ... rest unchanged
```

(Leave the rest of `validate` and `dup` exactly as they are — they already compare trimmed strings.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelValidationTest"`
Expected: PASS (all 7 — the 6 existing + the new one).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/LabelValidation.kt app/src/test/java/com/example/clipcc/ui/classify/LabelValidationTest.kt
git commit -m "feat: LabelValidation.normalize shared trim rule"
```

---

### Task 2: `LabelCsv.parse`

**Files:**
- Create: `app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt`

**Interfaces:**
- Consumes: `LabelValidation.normalize` (Task 1).
- Produces: `LabelCsv.MAX_BYTES/MAX_LABELS/MAX_LABEL_CHARS`; `LabelCsv.Parsed(labels: List<String>, labelTruncated: Boolean, dropped: Int)`; `LabelCsv.parse(text: String): Parsed`.

- [ ] **Step 1: Write the failing test** — create `LabelCsvTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: FAIL — `Unresolved reference: LabelCsv`.

- [ ] **Step 3: Create `LabelCsv.kt` with constants + `parse`:**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt
git commit -m "feat: LabelCsv.parse (rows -> labels, quotes, caps, dedup)"
```

---

### Task 3: `LabelCsv.read` (bounded, strict-UTF-8 reader)

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt`

**Interfaces:**
- Produces: `LabelCsv.Read(text: String, byteTruncated: Boolean)`; `LabelCsv.read(input: InputStream): Read` (throws `CharacterCodingException` on invalid UTF-8; does not close `input`).

- [ ] **Step 1: Write the failing test** — append to `LabelCsvTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: FAIL — `Unresolved reference: read`.

- [ ] **Step 3: Add `Read` + `read` to `LabelCsv.kt`** (add imports at top of file):

```kotlin
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
```

Inside `object LabelCsv`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: PASS (17 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt
git commit -m "feat: LabelCsv.read bounded strict-UTF-8 reader"
```

---

### Task 4: `LabelCsv.merge`

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt`

**Interfaces:**
- Consumes: `LabelValidation.normalize` (Task 1).
- Produces: `LabelCsv.Merged(labels: List<String>, inserted: Int)`; `LabelCsv.merge(existing: List<String>, parsed: List<String>, replace: Boolean): Merged`.

- [ ] **Step 1: Write the failing test** — append to `LabelCsvTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: FAIL — `Unresolved reference: merge`.

- [ ] **Step 3: Add `Merged` + `merge` to `LabelCsv.kt`:**

```kotlin
    data class Merged(val labels: List<String>, val inserted: Int)

    fun merge(existing: List<String>, parsed: List<String>, replace: Boolean): Merged {
        if (replace) return Merged(parsed, parsed.size)
        val have = existing.mapTo(HashSet()) { LabelValidation.normalize(it) }
        val added = parsed.filter { have.add(LabelValidation.normalize(it)) }
        return Merged(existing + added, added.size)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: PASS (20 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt
git commit -m "feat: LabelCsv.merge (replace / append-skip-dup)"
```

---

### Task 5: `LabelCsv` notice builders

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt`

**Interfaces:**
- Produces: `LabelCsv.zeroNotice(read: Read, parsed: Parsed): String`; `LabelCsv.replaceNotice(read: Read, parsed: Parsed): String`; `LabelCsv.appendNotice(read: Read, parsed: Parsed, merged: Merged): String`.

- [ ] **Step 1: Write the failing test** — append to `LabelCsvTest.kt`:

```kotlin
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
            LabelCsv.Merged(listOf("a", "b", "c"), inserted = 1))
        assertEquals("Added 1 labels, skipped 2 duplicates (file truncated)", n)
    }
    @Test fun appendNotice_all_present() {
        val n = LabelCsv.appendNotice(
            LabelCsv.Read("x", false), LabelCsv.Parsed(listOf("a", "b"), false, 0),
            LabelCsv.Merged(listOf("a", "b"), inserted = 0))
        assertEquals("All 2 labels already present", n)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: FAIL — `Unresolved reference: zeroNotice`.

- [ ] **Step 3: Add the notice builders to `LabelCsv.kt`:**

```kotlin
    private fun truncated(read: Read, parsed: Parsed) = read.byteTruncated || parsed.labelTruncated

    private fun suffix(read: Read, parsed: Parsed): String =
        (if (truncated(read, parsed)) " (file truncated)" else "") +
        (if (parsed.dropped > 0) " (${parsed.dropped} too long, skipped)" else "")

    fun zeroNotice(read: Read, parsed: Parsed): String =
        if (truncated(read, parsed)) "No complete labels before the file was truncated"
        else "No labels found in file"

    fun replaceNotice(read: Read, parsed: Parsed): String =
        "Loaded ${parsed.labels.size} labels" + suffix(read, parsed)

    fun appendNotice(read: Read, parsed: Parsed, merged: Merged): String {
        val skipped = parsed.labels.size - merged.inserted
        val head = if (merged.inserted > 0)
            "Added ${merged.inserted} labels" + (if (skipped > 0) ", skipped $skipped duplicates" else "")
        else "All ${parsed.labels.size} labels already present"
        return head + suffix(read, parsed)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.LabelCsvTest"`
Expected: PASS (25 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/LabelCsv.kt app/src/test/java/com/example/clipcc/ui/classify/LabelCsvTest.kt
git commit -m "feat: LabelCsv notice builders (zero/replace/append precedence)"
```

---

### Task 6: `LabelTarget` + ViewModel `setLabelList` (tested target routing)

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/ClassifyModels.kt` (add enum)
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/ClassifyViewModel.kt` (add `setLabelList`)
- Test: `app/src/test/java/com/example/clipcc/ui/classify/ClassifyViewModelTest.kt`

**Interfaces:**
- Consumes: existing `ClassifyViewModel.setLabels(positives, negatives)`; existing `_state.value.setup.{positives,negatives}`.
- Produces: `enum class LabelTarget { POSITIVE, NEGATIVE }`; `ClassifyViewModel.setLabelList(target: LabelTarget, list: List<String>)`.

- [ ] **Step 1: Write the failing test** — append to `ClassifyViewModelTest.kt`:

```kotlin
    @Test fun setLabelList_positive_changes_only_positives() {
        val v = vm(FakeClassifier(okResult()))
        v.setMode(AggMode.CONTRAST)
        v.setLabels(positives = listOf("p1"), negatives = listOf("n1"))
        v.setLabelList(LabelTarget.POSITIVE, listOf("x", "y"))
        assertEquals(listOf("x", "y"), v.state.value.setup.positives)
        assertEquals(listOf("n1"), v.state.value.setup.negatives)
    }
    @Test fun setLabelList_negative_changes_only_negatives() {
        val v = vm(FakeClassifier(okResult()))
        v.setMode(AggMode.CONTRAST)
        v.setLabels(positives = listOf("p1"), negatives = listOf("n1"))
        v.setLabelList(LabelTarget.NEGATIVE, listOf("z"))
        assertEquals(listOf("p1"), v.state.value.setup.positives)
        assertEquals(listOf("z"), v.state.value.setup.negatives)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.ClassifyViewModelTest"`
Expected: FAIL — `Unresolved reference: LabelTarget` / `setLabelList`.

- [ ] **Step 3a: Add the enum.** In `ClassifyModels.kt`, next to `enum class AggMode`:

```kotlin
enum class LabelTarget { POSITIVE, NEGATIVE }
```

- [ ] **Step 3b: Add `setLabelList`.** In `ClassifyViewModel.kt`, next to `setLabels`:

```kotlin
    /** Routes a committed list to the correct field (single tested seam for CSV-import targeting). */
    fun setLabelList(target: LabelTarget, list: List<String>) {
        val s = _state.value.setup
        when (target) {
            LabelTarget.POSITIVE -> setLabels(list, s.negatives)
            LabelTarget.NEGATIVE -> setLabels(s.positives, list)
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.ClassifyViewModelTest"`
Expected: PASS (existing 9 + 2 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/ClassifyModels.kt app/src/main/java/com/example/clipcc/ui/classify/ClassifyViewModel.kt app/src/test/java/com/example/clipcc/ui/classify/ClassifyViewModelTest.kt
git commit -m "feat: LabelTarget + ViewModel.setLabelList target routing"
```

---

### Task 7: `SetupCard` Import CSV button + dialog (UI glue)

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/SetupCard.kt`

**Interfaces:**
- Consumes: `LabelCsv.{read,parse,merge,zeroNotice,replaceNotice,appendNotice}`, `LabelTarget`, `ClassifyViewModel.setLabelList`.
- Produces: nothing other tasks depend on (terminal UI wiring).

> No unit test — this is thin Compose glue with no branching logic that isn't already covered by `LabelCsvTest`/`ClassifyViewModelTest`. Verified by compilation + the full JVM suite (Task 8) and a deferred manual device check (Step 4).

- [ ] **Step 1: Add imports** at the top of `SetupCard.kt` (alongside the existing imports):

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

(`rememberCoroutineScope`, `LocalContext`, `mutableStateOf`, `remember`, `Text`, `TextButton`, `AlertDialog`, `OutlinedTextField`, `Arrangement.spacedBy`, `dp` are already imported via the existing `material3.*`, `runtime.*`, `layout.*`, `unit.dp` wildcards / explicit imports in this file.)

- [ ] **Step 2: Add the `ImportCsvButton` composable + its preview holder** at the bottom of `SetupCard.kt`:

```kotlin
private data class ImportPreview(
    val read: LabelCsv.Read,
    val parsed: LabelCsv.Parsed,
    val append: LabelCsv.Merged,
    val existingCount: Int,
)

@Composable
private fun ImportCsvButton(target: LabelTarget, vm: ClassifyViewModel, running: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val read = ctx.contentResolver.openInputStream(uri)?.use { LabelCsv.read(it) }
                        ?: error("null stream")
                    read to LabelCsv.parse(read.text)
                }
            }
            res.fold(
                onSuccess = { (read, parsed) ->
                    if (parsed.labels.isEmpty()) {
                        notice = LabelCsv.zeroNotice(read, parsed); preview = null
                    } else {
                        val s = vm.state.value.setup
                        val current = if (target == LabelTarget.POSITIVE) s.positives else s.negatives
                        notice = null
                        preview = ImportPreview(
                            read, parsed,
                            LabelCsv.merge(current, parsed.labels, replace = false), current.size)
                    }
                },
                onFailure = { notice = "Couldn't read file (not valid text)"; preview = null },
            )
        }
    }

    TextButton(
        onClick = {
            notice = null
            picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/*"))
        },
        enabled = !running,
    ) { Text("Import CSV") }

    notice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Import ${p.parsed.labels.size} labels") },
            text = {
                val extra = (if (p.read.byteTruncated || p.parsed.labelTruncated) " File was truncated." else "") +
                    (if (p.parsed.dropped > 0) " ${p.parsed.dropped} row(s) too long, skipped." else "")
                Text("Append to the current ${p.existingCount}, or replace them?$extra")
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        vm.setLabelList(target, p.append.labels)
                        notice = LabelCsv.appendNotice(p.read, p.parsed, p.append); preview = null
                    }) { Text("Append (${p.append.inserted} new)") }
                    TextButton(onClick = {
                        vm.setLabelList(target, p.parsed.labels)
                        notice = LabelCsv.replaceNotice(p.read, p.parsed); preview = null
                    }) { Text("Replace") }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
}
```

- [ ] **Step 3: Wire the button into `LabelEditor`.** Replace the existing `LabelEditor` body so each list is followed by its import button:

```kotlin
@Composable
private fun LabelEditor(state: SetupState, vm: ClassifyViewModel, running: Boolean) {
    if (state.mode == AggMode.CONTRAST) {
        Text("Positive labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.positives, running) { vm.setLabels(it, state.negatives) }
        ImportCsvButton(LabelTarget.POSITIVE, vm, running)
        Text("Negative labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.negatives, running) { vm.setLabels(state.positives, it) }
        ImportCsvButton(LabelTarget.NEGATIVE, vm, running)
    } else {
        Text("Labels", style = MaterialTheme.typography.labelMedium)
        EditableList(state.positives, running) { vm.setLabels(it, state.negatives) }
        ImportCsvButton(LabelTarget.POSITIVE, vm, running)
    }
}
```

- [ ] **Step 4: Compile + (deferred) manual device check**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Deferred manual check (same class as the Plan-3 SAF-picker screenshots; picker not scriptable): install, pick a CSV via "Import CSV", confirm Replace/Append updates the list and the notice text. Document under the report's manual-steps section.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/SetupCard.kt
git commit -m "feat: Import CSV button + Replace/Append dialog in Classify setup"
```

---

### Task 8: Full-suite gate

**Files:** none (verification only).

- [ ] **Step 1: Run the full JVM unit suite**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; previous 78 + 25 (`LabelCsvTest`) + 2 (`ClassifyViewModelTest`) + 1 (`LabelValidationTest`) all green, 0 failures.

- [ ] **Step 2: Confirm the app assembles**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: No commit needed** (verification only). If anything failed, fix in the owning task and re-run.

---

## Self-Review

- **Spec coverage:** behavior contract → Tasks 6/7; merge identity → Task 1; caps table → Tasks 2/3; pure layer (read/parse/merge) → Tasks 2–4; notices/precedence → Task 5; ViewModel routing → Task 6; UI glue + disabled-while-running + dialog → Task 7; testing plan → Tasks 2–6, gate Task 8; non-goals → not implemented (correct). All sections mapped.
- **Placeholders:** none — every step has runnable code/commands.
- **Type consistency:** `LabelCsv.{Read,Parsed,Merged}` and `read/parse/merge/zeroNotice/replaceNotice/appendNotice` signatures match across Tasks 2–7; `LabelTarget` + `setLabelList` match across Tasks 6–7; `LabelValidation.normalize` defined in Task 1 and consumed in Tasks 2/4.
