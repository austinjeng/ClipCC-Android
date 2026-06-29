# Classify Results Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Classify results' two bar charts + unsorted text dump with a bounded ranked confidence-meter list (tap-to-expand), restyle + cap the TEMPORAL extras, bound the setup label editor, and hide CONTRAST behind a 20-tap reveal.

**Architecture:** All sorting/formatting/cap logic lives in a pure `ScoreView` object (JVM-tested). The ViewModel gains a tested unlock flag + a `dedupeLabels` repair. The Compose layer (`MeterBar`, `ResultsSection`, `ModeExtras`, `SetupCard`) is thin glue that renders bounded top-K. No engine/scoring change.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), kotlinx-coroutines. No new dependencies.

## Global Constraints

- **Pure-JVM tests only** (`:app:testDebugUnitTest`); the Compose surfaces are verified by `:app:assembleDebug` + a deferred manual UAT checklist (SAF/run not scriptable). No instrumented test added.
- **No engine/scoring change** — UI re-renders existing `AggregationResult`/`RunMeta`/`thumbnails` plus one ViewModel flag.
- **All result numeric formatting goes through `ScoreView` with `Locale.US`** (`pct`/`signedCos`/`secs`). Never raw `"%.1f".format(...)` in the result UI.
- **Caps (constants):** `ScoreView.COLLAPSED=5`, `MAX_ROWS=50`, `TIMELINE_SERIES=6`, `SUMMARY_ROWS=20`, `SEGMENT_ROWS=50`; `SetupCard.EDIT_CAP=50`; `ClassifyViewModel.CONTRAST_UNLOCK_TAPS=20`.
- **Labels are case-sensitive** — `LabelValidation.normalize` trims only, never lowercases; `dedupeLabels` uses it.
- **`MeterBar` is decorative** (`clearAndSetSemantics {}`); the adjacent % text carries the value.
- **CONTRAST unlock is an affordance, not an invariant** — no guard on `setMode`; restored `mode==CONTRAST` stays visible.
- JVM test command (JBR 21):
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest`
  Filter a class with `--tests "com.example.clipcc.ui.classify.ScoreViewTest"`. assembleDebug:
  `… :app:assembleDebug`.
- Branch: `feat/classify-results-redesign` (already created off `main`).

## File structure

- **Create** `app/src/main/java/com/example/clipcc/ui/classify/ScoreView.kt` — pure helpers (Task 1).
- **Create** `app/src/main/java/com/example/clipcc/ui/classify/MeterBar.kt` — composable (Task 3).
- **Modify** `…/ui/classify/ClassifyModels.kt` — `SetupState` += `contrastUnlocked`, `temporalTaps` (Task 2).
- **Modify** `…/ui/classify/ClassifyViewModel.kt` — unlock + persist/restore + `dedupeLabels` (Task 2).
- **Modify** `…/ui/classify/ResultsSection.kt` — full rewrite (Task 4).
- **Modify** `…/ui/classify/ModeExtras.kt` — remove MAX branch, restyle/cap TEMPORAL (Task 5).
- **Modify** `…/ui/classify/SetupCard.kt` — mode filter, editor cap, dedupe button (Task 6).
- **Create test** `app/src/test/java/com/example/clipcc/ui/classify/ScoreViewTest.kt` (Task 1).
- **Modify test** `…/test/…/ui/classify/ClassifyViewModelTest.kt` (Task 2).

---

### Task 1: `ScoreView` pure helper

**Files:**
- Create: `app/src/main/java/com/example/clipcc/ui/classify/ScoreView.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/classify/ScoreViewTest.kt`

**Interfaces:**
- Consumes: `com.example.clipcc.engine.ScoreItem`, `com.example.clipcc.engine.LabelSummary`, `AggMode` (same package).
- Produces: `ScoreView.{COLLAPSED,MAX_ROWS,TIMELINE_SERIES,SUMMARY_ROWS,SEGMENT_ROWS}`,
  `ranked(List<ScoreItem>): List<ScoreItem>`, `pct(Double): String`, `signedCos(Double): String`,
  `secs(Double): String`, `topSummaries(List<LabelSummary>, Int): List<LabelSummary>`,
  `topActiveLabels(List<LabelSummary>, Int): List<String>`, `visibleModes(Boolean, AggMode): List<AggMode>`.

- [ ] **Step 1: Write the failing test** — create `ScoreViewTest.kt`:

```kotlin
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
    @Test fun ranked_take_caps_a_thousand_at_MAX_ROWS() {
        val many = (1..1000).map { s("l$it", it / 1000.0) }
        assertEquals(50, ScoreView.ranked(many).take(ScoreView.MAX_ROWS).size)
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
        assertEquals(5, ScoreView.COLLAPSED); assertEquals(50, ScoreView.MAX_ROWS)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.ScoreViewTest"`
Expected: FAIL — `Unresolved reference: ScoreView`.

- [ ] **Step 3: Create `ScoreView.kt`:**

```kotlin
package com.example.clipcc.ui.classify

import com.example.clipcc.engine.LabelSummary
import com.example.clipcc.engine.ScoreItem
import java.util.Locale

/** Pure, Android-free formatting / sorting / cap helpers for the Classify results UI. */
object ScoreView {
    const val COLLAPSED = 5
    const val MAX_ROWS = 50
    const val TIMELINE_SERIES = 6
    const val SUMMARY_ROWS = 20
    const val SEGMENT_ROWS = 50

    /** Confidence desc; Kotlin's sort is stable so ties keep input order. */
    fun ranked(scores: List<ScoreItem>): List<ScoreItem> = scores.sortedByDescending { it.confidence }

    fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100)
    fun signedCos(v: Double): String = String.format(Locale.US, "%+.3f", v)
    fun secs(v: Double): String = String.format(Locale.US, "%.1f s", v)

    fun topSummaries(list: List<LabelSummary>, k: Int): List<LabelSummary> =
        list.filter { it.segmentCount > 0 }.sortedByDescending { it.durationWeightedConfidence }.take(k)

    fun topActiveLabels(summaries: List<LabelSummary>, k: Int): List<String> =
        topSummaries(summaries, k).map { it.label }

    fun visibleModes(unlocked: Boolean, current: AggMode): List<AggMode> =
        AggMode.entries.filter { it != AggMode.CONTRAST || unlocked || current == AggMode.CONTRAST }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.ScoreViewTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/ScoreView.kt app/src/test/java/com/example/clipcc/ui/classify/ScoreViewTest.kt
git commit -m "feat: ScoreView pure helpers (ranked, formatting, caps, visibleModes)"
```

---

### Task 2: ViewModel unlock + `dedupeLabels`

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/ClassifyModels.kt` (SetupState fields)
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/ClassifyViewModel.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/classify/ClassifyViewModelTest.kt`

**Interfaces:**
- Consumes: existing `setLabels`, `updateSetup`, `LabelValidation.normalize`, `SavedStateHandle`.
- Produces: `SetupState.contrastUnlocked: Boolean`, `SetupState.temporalTaps: Int`;
  `ClassifyViewModel.CONTRAST_UNLOCK_TAPS: Int` (companion), `ClassifyViewModel.dedupeLabels()`.

- [ ] **Step 1: Write the failing tests** — append to `ClassifyViewModelTest.kt` (inside the class):

```kotlin
    @Test fun temporal_taps_unlock_contrast_at_threshold() {
        val v = vm(FakeClassifier(okResult()))
        repeat(ClassifyViewModel.CONTRAST_UNLOCK_TAPS - 1) { v.setMode(AggMode.TEMPORAL) }
        assertFalse(v.state.value.setup.contrastUnlocked)
        v.setMode(AggMode.TEMPORAL)
        assertTrue(v.state.value.setup.contrastUnlocked)
    }
    @Test fun non_temporal_taps_do_not_unlock() {
        val v = vm(FakeClassifier(okResult()))
        repeat(30) { v.setMode(AggMode.MAX); v.setMode(AggMode.MEAN) }
        assertFalse(v.state.value.setup.contrastUnlocked)
    }
    @Test fun contrast_unlock_restored_from_saved_state() {
        val handle = androidx.lifecycle.SavedStateHandle()
        val v1 = ClassifyViewModel(FakeClassifier(okResult()), listOf(readyModel), { _, _ -> null }, dispatcher, handle)
        repeat(ClassifyViewModel.CONTRAST_UNLOCK_TAPS) { v1.setMode(AggMode.TEMPORAL) }
        val v2 = ClassifyViewModel(FakeClassifier(okResult()), listOf(readyModel), { _, _ -> null }, dispatcher, handle)
        assertTrue(v2.state.value.setup.contrastUnlocked)
    }
    @Test fun dedupeLabels_drops_later_dups_keeps_blanks_and_clears_error() {
        val v = vm(FakeClassifier(okResult()))
        v.setMode(AggMode.CONTRAST)
        v.setLabels(positives = listOf("cat", "dog", ""), negatives = listOf("cat", "bird"))
        assertEquals("Duplicate label: cat", v.state.value.setup.validationError)
        v.dedupeLabels()
        assertEquals(listOf("cat", "dog", ""), v.state.value.setup.positives)  // blank kept, first cat kept
        assertEquals(listOf("bird"), v.state.value.setup.negatives)            // later cat dropped
        assertNull(v.state.value.setup.validationError)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.ClassifyViewModelTest"`
Expected: FAIL — `Unresolved reference: contrastUnlocked` / `CONTRAST_UNLOCK_TAPS` / `dedupeLabels`.

- [ ] **Step 3a: Add SetupState fields.** In `ClassifyModels.kt`, inside `data class SetupState(`, add these two fields (e.g. right after `val precisionUserSet: Boolean = false,`):

```kotlin
    val contrastUnlocked: Boolean = false,
    val temporalTaps: Int = 0,
```

- [ ] **Step 3b: Add the constant + unlock + persist/restore + dedupe.** In `ClassifyViewModel.kt`:

Add a companion object (anywhere in the class body, e.g. just before the closing brace):
```kotlin
    companion object { const val CONTRAST_UNLOCK_TAPS = 20 }
```

Replace `fun setMode(m: AggMode) = updateSetup { it.copy(mode = m) }` with:
```kotlin
    fun setMode(m: AggMode) = updateSetup {
        if (m == AggMode.TEMPORAL) {
            val taps = it.temporalTaps + 1
            it.copy(mode = m, temporalTaps = taps,
                contrastUnlocked = it.contrastUnlocked || taps >= CONTRAST_UNLOCK_TAPS)
        } else it.copy(mode = m)
    }
```

In `persist(s)`, add (after `savedState["precisionUserSet"] = s.precisionUserSet`):
```kotlin
        savedState["contrastUnlocked"] = s.contrastUnlocked
        savedState["temporalTaps"] = s.temporalTaps
```

In `init`'s `SetupState(...)` restore, add (after `precisionUserSet = savedState["precisionUserSet"] ?: false,`):
```kotlin
            contrastUnlocked = savedState["contrastUnlocked"] ?: false,
            temporalTaps = savedState["temporalTaps"] ?: 0,
```

Add the dedupe action (e.g. after `setLabelList`):
```kotlin
    /** Repair for a duplicate error: drop non-blank labels whose normalize was already seen earlier
     *  in positives++negatives scan order (the order LabelValidation scans); blanks preserved. */
    fun dedupeLabels() = updateSetup {
        val seen = HashSet<String>()
        fun keep(s: String): Boolean { val n = LabelValidation.normalize(s); return n.isEmpty() || seen.add(n) }
        it.copy(positives = it.positives.filter { s -> keep(s) },
            negatives = it.negatives.filter { s -> keep(s) })
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest --tests "com.example.clipcc.ui.classify.ClassifyViewModelTest"`
Expected: PASS (existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/ClassifyModels.kt app/src/main/java/com/example/clipcc/ui/classify/ClassifyViewModel.kt app/src/test/java/com/example/clipcc/ui/classify/ClassifyViewModelTest.kt
git commit -m "feat: contrast 20-tap unlock + dedupeLabels repair (ViewModel)"
```

---

### Task 3: `MeterBar` composable

**Files:**
- Create: `app/src/main/java/com/example/clipcc/ui/classify/MeterBar.kt`

**Interfaces:**
- Produces: `@Composable fun MeterBar(fraction: Float, color: Color = …, height: Dp = 6.dp)`.

> No unit test — thin decorative composable. Verified by `:app:assembleDebug`.

- [ ] **Step 1: Create `MeterBar.kt`:**

```kotlin
package com.example.clipcc.ui.classify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Decorative score meter. The adjacent % text carries the value for accessibility. */
@Composable
fun MeterBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 6.dp) {
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))
            .background(color.copy(alpha = 0.15f)).clearAndSetSemantics {}
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height)
                .clip(RoundedCornerShape(height / 2)).background(color)
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/MeterBar.kt
git commit -m "feat: MeterBar decorative confidence bar"
```

---

### Task 4: `ResultsSection` rewrite

**Files:**
- Modify (full rewrite): `app/src/main/java/com/example/clipcc/ui/classify/ResultsSection.kt`

**Interfaces:**
- Consumes: `ScoreView.{ranked,pct,signedCos,secs,COLLAPSED,MAX_ROWS}`, `MeterBar`, `ModeExtras` (existing),
  `RunState.Success`, `RunResult`, `com.example.clipcc.engine.ScoreItem`, `android.graphics.Bitmap`.
- Produces: `@Composable fun ResultsSection(success: RunState.Success)` (signature unchanged).

> No unit test — thin UI; sorting/formatting/caps are pure-tested in Task 1. Verified by `:app:assembleDebug` + the full JVM suite staying green. Transient note: until Task 5, MAX mode also shows the legacy `MaxExtras` block (removed in Task 5) — harmless overlap between commits.

- [ ] **Step 1: Replace the entire file contents** of `ResultsSection.kt`:

```kotlin
package com.example.clipcc.ui.classify

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.clipcc.engine.ScoreItem

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsSection(success: RunState.Success) {
    val r = success.result
    val agg = r.result
    val rows = remember(success) { ScoreView.ranked(agg.scores) }
    var showAll by remember(success) { mutableStateOf(false) }
    var expanded by remember(success) { mutableStateOf(emptySet<String>()) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("BEST MATCH", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(agg.bestMatch.label, style = MaterialTheme.typography.headlineSmall)
                Text(ScoreView.pct(agg.bestMatch.confidence), style = MaterialTheme.typography.titleLarge)
                MeterBar(agg.bestMatch.confidence.toFloat())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(r.meta.modelId, r.meta.requestedBackend.label, "${r.meta.frameCount}f",
                        "${r.meta.elapsedMs} ms", r.meta.scoreSemantics).forEach { chip ->
                        AssistChip(onClick = {},
                            label = { Text(chip, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Text("live node coverage not profiled — see Benchmark",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("All labels (${rows.size})", style = MaterialTheme.typography.labelLarge)
            Text("by confidence", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        rows.take(if (showAll) ScoreView.MAX_ROWS else ScoreView.COLLAPSED).forEachIndexed { i, item ->
            ScoreRow(i + 1, item, r.thumbnails, item.label in expanded) {
                expanded = if (item.label in expanded) expanded - item.label else expanded + item.label
            }
        }
        if (rows.size > ScoreView.COLLAPSED) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "⌃ show less"
                     else "⌄ show ${minOf(rows.size, ScoreView.MAX_ROWS) - ScoreView.COLLAPSED} more")
            }
        }
        if (showAll && rows.size > ScoreView.MAX_ROWS) {
            Text("Top ${ScoreView.MAX_ROWS} of ${rows.size} — import fewer labels for full detail",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        ModeExtras(r)
    }
}

@Composable
private fun ScoreRow(rank: Int, item: ScoreItem, thumbnails: Map<Int, Bitmap>,
                     expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onToggle() },
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(28.dp))
            Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(ScoreView.pct(item.confidence), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium)
        }
        MeterBar(item.confidence.toFloat())
        if (expanded) {
            Text("cosine ${ScoreView.signedCos(item.rawSimilarity)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.peakFrameIndex?.let { idx ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    thumbnails[idx]?.let {
                        Image(it.asImageBitmap(), contentDescription = "${item.label} peak frame",
                            modifier = Modifier.size(72.dp))
                    }
                    Text("peak @ ${ScoreView.secs(item.approxTimestampSeconds ?: 0.0)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles + full suite still green**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` (no remaining `BarChart`/`ChartData` references in this file).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/ResultsSection.kt
git commit -m "feat: ranked confidence-meter results list (bounded, tap-to-expand)"
```

---

### Task 5: `ModeExtras` — drop MAX branch, restyle + cap TEMPORAL

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/ModeExtras.kt`

**Interfaces:**
- Consumes: `ScoreView.{topActiveLabels,topSummaries,pct,secs,TIMELINE_SERIES,SUMMARY_ROWS,SEGMENT_ROWS}`,
  `MeterBar`, `TimelineChart`/`TimelineSeries`/`TimelineBand` (existing), `RunResult`, `ContrastResult`.
- Produces: `ModeExtras(r)` routing only temporal/contrast; restyled `TemporalExtras`.

> No unit test — UI; the cap/selection logic is pure-tested in Task 1. Verified by `:app:assembleDebug`.

- [ ] **Step 1: Replace `ModeExtras` and `TemporalExtras`, and delete `MaxExtras`.** Edit `ModeExtras.kt`:

Replace the `ModeExtras` function body so it no longer routes MAX:
```kotlin
@Composable
fun ModeExtras(r: RunResult) {
    val agg = r.result
    when {
        agg.temporal != null -> TemporalExtras(r)
        agg.contrast != null -> ContrastExtras(agg.contrast!!)
        else -> {}
    }
}
```

Delete the entire `private fun MaxExtras(r: RunResult) { … }` function.

Replace the entire `private fun TemporalExtras(r: RunResult) { … }` with:
```kotlin
@Composable
private fun TemporalExtras(r: RunResult) {
    val t = r.result.temporal!!
    val shown = ScoreView.topActiveLabels(t.labelSummaries, ScoreView.TIMELINE_SERIES)
    if (shown.isEmpty()) {
        Text("No segments met threshold ${ScoreView.pct(t.effectiveThreshold)}",
            style = MaterialTheme.typography.bodyMedium)
        return
    }
    val palette = listOf(Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF2E7D32),
        Color(0xFFC62828), Color(0xFF6A1B9A), Color(0xFF00838F))
    val colorOf = shown.withIndex().associate { (i, lbl) -> lbl to palette[i % palette.size] }
    val series = shown.map { lbl ->
        TimelineSeries(lbl, colorOf.getValue(lbl), t.timeline.map { fr -> (fr.scores[lbl] ?: 0.0).toFloat() })
    }
    val total = (t.timeline.lastOrNull()?.timestamp ?: 1.0).coerceAtLeast(1e-6)
    val bands = t.segments.filter { it.label in colorOf }.map { seg ->
        TimelineBand(colorOf.getValue(seg.label),
            (seg.startTime / total).toFloat(), (seg.endTime / total).toFloat())
    }

    Text("Timeline", style = MaterialTheme.typography.labelLarge)
    Text("threshold ${ScoreView.pct(t.effectiveThreshold)}${if (t.thresholdWasDefaulted) " (default)" else ""}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    TimelineChart(series, threshold = t.effectiveThreshold.toFloat(), bands = bands)

    Text("Segments", style = MaterialTheme.typography.labelLarge)
    t.segments.sortedByDescending { it.peakConfidence }.take(ScoreView.SEGMENT_ROWS).forEach { seg ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(seg.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${ScoreView.secs(seg.startTime)}–${ScoreView.secs(seg.endTime)} · ${ScoreView.secs(seg.duration)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { MeterBar(seg.peakConfidence.toFloat()) }
                Text(ScoreView.pct(seg.peakConfidence), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (t.segments.size > ScoreView.SEGMENT_ROWS) {
        Text("… ${t.segments.size - ScoreView.SEGMENT_ROWS} more segments",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }

    Text("Label summaries", style = MaterialTheme.typography.labelLarge)
    ScoreView.topSummaries(t.labelSummaries, ScoreView.SUMMARY_ROWS).forEach { ls ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ls.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${ls.segmentCount} seg · active ${ScoreView.secs(ls.totalActiveDuration)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.width(80.dp)) { MeterBar(ls.durationWeightedConfidence.toFloat()) }
            Text(ScoreView.pct(ls.durationWeightedConfidence), style = MaterialTheme.typography.bodySmall)
        }
    }
    val activeCount = t.labelSummaries.count { it.segmentCount > 0 }
    if (activeCount > ScoreView.SUMMARY_ROWS) {
        Text("… ${activeCount - ScoreView.SUMMARY_ROWS} more labels",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
```

- [ ] **Step 2: Fix imports.** In `ModeExtras.kt` **add** these two imports:
  `import androidx.compose.ui.Alignment` and `import androidx.compose.ui.text.font.FontWeight`
  (the new code uses `Alignment.CenterVertically` and `FontWeight.Medium` unqualified). **Remove**
  `import com.example.clipcc.engine.ScoreItem` (only the deleted `MaxExtras` used it). `Box`, `Row`,
  `Column`, `width`, `weight`, `Arrangement` come from the existing `androidx.compose.foundation.layout.*`
  wildcard; `Color`/`Modifier` are already imported.

- [ ] **Step 3: Verify it compiles**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/ModeExtras.kt
git commit -m "feat: restyle + cap TEMPORAL extras (series/bands/segments/summaries, empty state); drop MaxExtras"
```

---

### Task 6: `SetupCard` — mode filter, editor cap, dedupe button

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/classify/SetupCard.kt`

**Interfaces:**
- Consumes: `ScoreView.visibleModes`, `ClassifyViewModel.dedupeLabels`, `state.contrastUnlocked`.
- Produces: filtered Mode selector; capped `EditableList`; `Remove duplicates` affordance.

> No unit test — UI; `visibleModes`/`dedupeLabels` are pure/VM-tested. Verified by `:app:assembleDebug`.

- [ ] **Step 1: Filter the Mode selector.** Replace the existing Mode block (the `Text("Mode", …)` +
  `SingleChoiceSegmentedButtonRow` that iterates `AggMode.entries`) with:

```kotlin
        Text("Mode", style = MaterialTheme.typography.labelMedium)
        val modes = ScoreView.visibleModes(state.contrastUnlocked, state.mode)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = state.mode == m, onClick = { vm.setMode(m) }, enabled = !running,
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(m.name.lowercase()) }
            }
        }
```

- [ ] **Step 2: Add the dedupe affordance.** Replace the line
  `state.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }` with:

```kotlin
        state.validationError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error)
            if (err.startsWith("Duplicate label:")) {
                TextButton(onClick = { vm.dedupeLabels() }, enabled = !running) { Text("Remove duplicates") }
            }
        }
```

- [ ] **Step 3: Cap the label editor.** Add a file-scope constant near the top of `SetupCard.kt` (after the
  imports, before the first `@Composable`):

```kotlin
private const val EDIT_CAP = 50
```

Replace the entire `private fun EditableList(...)` with:
```kotlin
@Composable
private fun EditableList(items: List<String>, running: Boolean, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.take(EDIT_CAP).forEachIndexed { i, v ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = v, onValueChange = { nv ->
                    onChange(items.toMutableList().also { it[i] = nv })
                }, modifier = Modifier.weight(1f), enabled = !running, singleLine = true)
                TextButton(onClick = { onChange(items.toMutableList().also { it.removeAt(i) }) },
                    enabled = !running) { Text("×") }
            }
        }
        if (items.size > EDIT_CAP) {
            Text("+ ${items.size - EDIT_CAP} more imported labels",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = { onChange(items + "") }, enabled = !running) { Text("+ Add label") }
        }
    }
}
```

- [ ] **Step 4: Verify it compiles + full suite green**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/classify/SetupCard.kt
git commit -m "feat: hide CONTRAST until unlock, cap label editor at 50, dedupe-repair button"
```

---

### Task 7: Full-suite gate

**Files:** none (verification only).

- [ ] **Step 1: Full JVM suite**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; previous suite + `ScoreViewTest` (7) + `ClassifyViewModelTest` (4 new), 0 failures.

- [ ] **Step 2: App assembles**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: No commit** (verification only). Fix any failure in the owning task and re-run.

Deferred manual UAT (record in the phase report; picker/run not scriptable): (a) collapsed list shows 5
+ "show more"; (b) expand-all caps at 50 with "Top 50 of N" on a large CSV; (c) tap row → cosine (+ MAX
thumbnail, fallback when missing); (d) rerun with reordered labels → no stale expansion on the wrong
label; (e) mode selector hides CONTRAST, 20 temporal taps reveal it, CONTRAST runs + shows verdict;
(f) TEMPORAL capped (≤6 series + matching bands) with count notes, all-below-threshold shows
`No segments met threshold 50.0%`; (g) a 1000-label CSV leaves setup responsive (50 fields + `+N more`,
no `+ Add label`); (h) a 60-label CONTRAST import with a duplicate below row 50 shows
`Remove duplicates` that clears it in one tap.

---

## Self-Review

- **Spec coverage:** MeterBar §1→T3; ScoreView §2→T1; ResultsSection best-match+ranked list §3→T4;
  TEMPORAL restyle/caps/bands/empty §4→T5; CONTRAST hide+unlock §5→T2(logic)+T6(selector); setup editor
  cap §6→T6; dedupe repair (M1)→T2(logic)+T6(button); scale invariants→T1 constants + T4/T5 caps;
  Locale.US→T1; a11y decorative→T3; testing→T1/T2 + T7 gate + UAT. All mapped.
- **Placeholder scan:** none — every step has runnable code/commands.
- **Type consistency:** `ScoreView.{ranked,pct,signedCos,secs,topSummaries,topActiveLabels,visibleModes}`
  + constants used identically in T4/T5/T6; `contrastUnlocked`/`temporalTaps`/`CONTRAST_UNLOCK_TAPS`/
  `dedupeLabels` defined T2, consumed T6; `MeterBar(fraction: Float)` defined T3, called with `.toFloat()`
  in T4/T5; `TimelineSeries`/`TimelineBand`/`TimelineChart` signatures match the existing chart file.
```
