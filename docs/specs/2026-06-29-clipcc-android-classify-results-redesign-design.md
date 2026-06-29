# ClipCC-Android — Classify results redesign

**Date:** 2026-06-29 · **Status:** approved (review-AJ incorporated), pre-plan ·
**Branch:** `feat/classify-results-redesign` off `main`.

## Goal

Make the Classify results readable when there are many labels (e.g. a CSV import of up to the
`MAX_LABELS = 1000` cap). Today `ResultsSection` renders two full-width bar charts (one bar per
label) plus a `forEach` that dumps every label as a cramped, **unsorted** `bodySmall` line — a wall
of text past ~8 labels. Replace that with a **bounded** ranked confidence-meter list, restyle the
TEMPORAL extras (also bounded), and hide CONTRAST behind a 20-tap reveal.

## Scale & state invariants (resolves the "many labels" goal)

`MAX_LABELS = 1000` is an **import/storage cap, not a render target.** The results surface never
renders an unbounded number of rows/series; it renders a **bounded top-K** with a count note, so
the existing single `verticalScroll` `Column` host (`ClipCCApp.kt`) stays valid and no
virtualization / `LazyColumn` (which would crash nested in a vertical scroll) is needed:

- **Ranked list:** collapsed to `COLLAPSED = 5`; "show more" expands to at most `MAX_ROWS = 50`
  rows. If `N > MAX_ROWS`, render the top `MAX_ROWS` and show `Top 50 of N — import fewer labels
  for full detail`. Worst case ≈ 50 row composables → a plain `Column` is fine.
- **TEMPORAL:** timeline series capped to the top `TIMELINE_SERIES = 6` **active** labels (≥1
  segment) by duration-weighted confidence; shaded timeline **bands** are filtered to those same
  ≤6 series labels (never a band without a visible line — M1); label summaries to the top
  `SUMMARY_ROWS = 20` active labels by DWC; segments capped at `SEGMENT_ROWS = 50` (segments are
  activity-bounded, this is a safety ceiling). Each capped block shows a `… N more` count note. If
  **no** label is active (all scores below threshold), render none of these — show
  `No segments met threshold` instead (M2).
- **Setup editor:** `EditableList` renders at most `EDIT_CAP = 50` `OutlinedTextField`s. When the
  list exceeds it (large CSV import), show the first `EDIT_CAP` editable rows + a muted
  `+ N more imported labels` summary, and **hide** `+ Add label` (manage the overflow by clearing
  visible rows or re-importing). Under the cap, behavior is unchanged (all rows + `+ Add label`).
  This bounds the *setup* half of the screen so the 1000-label scenario stays responsive (H1).
- **State resets per result:** row-expansion and show-all state are hoisted to `ResultsSection`
  and keyed on the `success` instance (`remember(success)`), so a rerun (which can reorder labels)
  starts clean; expansion is tracked **by label**, never by row position.
- **CONTRAST unlock is a discoverability affordance, not an access invariant.** Hiding only reduces
  normal-mode clutter; it does not guard `setMode`. A restored `mode == CONTRAST` (process death)
  intentionally stays visible/usable within restored task state. A genuine cold start (no saved
  state) resets both `mode` (→ MEAN) and `contrastUnlocked`.

## What the result already carries (no engine changes)

`RunState.Success.result: RunResult` → `result: AggregationResult`:
- `scores: List<ScoreItem>` — `label`, `confidence` (sigmoid 0–1), `rawSimilarity` (signed cosine),
  optional `peakFrameIndex` + `approxTimestampSeconds` (MAX mode).
- `bestMatch`, `temporal: TemporalResult?`, `contrast: ContrastResult?`.
- `meta: RunMeta(modelId, requestedBackend, frameCount, elapsedMs, scoreSemantics)`,
  `thumbnails: Map<Int, Bitmap>`.

This is a **pure UI redesign** — it re-renders existing data plus one small ViewModel unlock flag.

## Components

### 1. `MeterBar` (new, `ui/classify/MeterBar.kt`)
```
@Composable fun MeterBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 6.dp)
```
Rounded track (`color.copy(alpha=0.15f)`) + fill `Box(Modifier.fillMaxWidth(fraction.coerceIn(0f,1f)))`.
**Decorative** — `Modifier.clearAndSetSemantics {}` so screen readers read the adjacent `%` text,
not a bare bar (L2). Reused by best-match card, score rows, TEMPORAL segment/summary rows.

### 2. `ScoreView` (new pure helper, `ui/classify/ScoreView.kt`) — JVM-tested, no Android types
```
object ScoreView {
    const val COLLAPSED = 5
    const val MAX_ROWS = 50
    const val TIMELINE_SERIES = 6
    const val SUMMARY_ROWS = 20
    const val SEGMENT_ROWS = 50
    fun ranked(scores: List<ScoreItem>): List<ScoreItem>     // sortedByDescending { confidence }, stable
    fun pct(v: Double): String                               // "87.3%"  (Locale.US, 1 dp)
    fun signedCos(v: Double): String                         // "+0.420" / "-0.050" (Locale.US, 3 dp)
    fun secs(v: Double): String                              // "3.2 s"  (Locale.US, 1 dp)
    fun topSummaries(list: List<LabelSummary>, k: Int): List<LabelSummary>  // active, by DWC desc, ≤k
    fun topActiveLabels(summaries: List<LabelSummary>, k: Int): List<String>// active (segmentCount>0), by DWC, ≤k
    fun visibleModes(unlocked: Boolean, current: AggMode): List<AggMode>    // see §5
}
```
All result numeric formatting goes through here with `Locale.US` (L1).

### 3. `ResultsSection` rewrite (`ui/classify/ResultsSection.kt`)
Hoisted state, reset per run:
```
var showAll by remember(success) { mutableStateOf(false) }
var expanded by remember(success) { mutableStateOf(emptySet<String>()) }   // keyed by label (M3)
```
Renders a padded `Column(spacedBy 16.dp)`:

- **Best-match card** (`ElevatedCard`): `BEST MATCH` overline (`labelMedium`, primary) · best label
  (`headlineSmall`) · `pct(bestMatch.confidence)` large · `MeterBar(bestMatch.confidence)` ·
  metadata as a `FlowRow` of small `AssistChip`s (`modelId`, `backend.label`, `${frameCount}f`,
  `${elapsedMs} ms`, `scoreSemantics`) (`@OptIn(ExperimentalLayoutApi::class)`) · the existing
  "live node coverage not profiled" note as a muted `outline` `bodySmall`.

- **Ranked list**: `val rows = ScoreView.ranked(scores)`; header `All labels (N)` · `by confidence`.
  Show `rows.take(if (showAll) MAX_ROWS else COLLAPSED)`:
  - **Row** (`Modifier.clickable { expanded = expanded.toggle(label) }`): rank · label
    (`bodyLarge`, Medium, `weight(1f)`) · `pct(confidence)` (right, Medium); below `MeterBar(confidence)`.
  - **Expanded** (`label in expanded`): `cosine ${signedCos(rawSimilarity)}` (muted); if
    `peakFrameIndex != null` → `thumbnails[idx]` (72.dp, omit if null) + `@ ${secs(approxTimestampSeconds ?: 0.0)}`.
  - Footer: if `N > COLLAPSED`, `TextButton` toggles `⌄ show ${min(N,MAX_ROWS)-COLLAPSED} more` /
    `⌃ show less`. If `showAll && N > MAX_ROWS`: muted note `Top $MAX_ROWS of $N — import fewer labels for full detail`.

- **Mode extras**: `ModeExtras(r)` (§4). The old separate `MaxExtras` block is **removed** (peak
  thumbnails now live in expanded rows).

The two `BarChart`s and the per-label text `forEach` are **deleted** from `ResultsSection`.

### 4. `ModeExtras` / TEMPORAL restyle (`ui/classify/ModeExtras.kt`)
- Routes: `temporal != null → TemporalExtras`; `contrast != null → ContrastExtras` (reached through
  the normal selector after unlock, or via restored/direct state since unlock is not an invariant;
  retained as-is, see §5); else nothing. (MAX branch removed.)
- **TemporalExtras** — `val shown = ScoreView.topActiveLabels(labelSummaries, TIMELINE_SERIES)`.
  If `shown.isEmpty()` → render only `Text("No segments met threshold ${pct(effectiveThreshold)}")`
  and stop (M2/L1 — keeps the threshold context). Otherwise
  keep `TimelineChart`, feeding it **only `shown`** for both series **and** bands (bands =
  `segments.filter { it.label in shown }`, so every band has a visible line — M1); caption
  `threshold ${pct(effectiveThreshold)}${if (defaulted) " (default)" else ""}`.
  - **Segments** (≤ `SEGMENT_ROWS`, by peak desc) → one row each: `label` (Medium) ·
    `${secs(startTime)}–${secs(endTime)}` · `${secs(duration)}` · `MeterBar(peakConfidence)` +
    `pct(peakConfidence)`. `… N more` note if capped.
  - **Label summaries** → `ScoreView.topSummaries(labelSummaries, SUMMARY_ROWS)` rows: `label` ·
    `${segmentCount} seg` · `active ${secs(totalActiveDuration)}` · `MeterBar(durationWeightedConfidence)` +
    `pct`. `… N more` note if capped.
- `ContrastExtras` left as-is (legacy UX; only reachable post-unlock; restyle out of scope).

### 5. CONTRAST hidden + 20-tap reveal
- **`ClassifyViewModel`**: `const val CONTRAST_UNLOCK_TAPS = 20`. `SetupState` gains
  `contrastUnlocked: Boolean = false` and `temporalTaps: Int = 0`. `setMode(m)`: if
  `m == AggMode.TEMPORAL`, `temporalTaps++`; when `temporalTaps >= CONTRAST_UNLOCK_TAPS`, set
  `contrastUnlocked = true`. Persist `contrastUnlocked` + `temporalTaps` in `SavedStateHandle`
  (alongside the existing `mode`/`positives`/… persistence). No guard on `setMode` — unlock is an
  affordance, not an invariant.
- **`SetupCard`** Mode selector: `val modes = ScoreView.visibleModes(state.contrastUnlocked, state.mode)`
  where `visibleModes = AggMode.entries.filter { it != CONTRAST || unlocked || current == CONTRAST }`;
  lay `SingleChoiceSegmentedButtonRow` over `modes` (so `itemShape(i, modes.size)` matches the
  visible count). Contrast is absent until unlocked; once shown it is fully selectable and runs its
  existing path. No visual hint.

### 6. Setup label editor cap (`SetupCard.EditableList`)
`private const val EDIT_CAP = 50`. Render `items.take(EDIT_CAP)` as editable `OutlinedTextField`s.
If `items.size > EDIT_CAP`: after the rows, a muted `+ ${items.size - EDIT_CAP} more imported labels`
summary, and **hide** the `+ Add label` button (overflow managed by removing visible rows or
re-importing). If `items.size <= EDIT_CAP`: unchanged (all rows + `+ Add label`). Bounds the setup
half of the screen at scale (H1). The full list is still what runs / persists / imports — the cap is
display-only. Applies to both positive and negative editors.

**Hidden-label validation repair (M1):** because the offending row of a duplicate error can be in
the hidden overflow (e.g. a CONTRAST cross-list overlap below row 50), a blocking
`Duplicate label: …` must be fixable without hunting. When `state.validationError` starts with
`Duplicate label:`, render a `Remove duplicates` `TextButton` beside the error that calls
`ClassifyViewModel.dedupeLabels()`: rebuild `positives`/`negatives` dropping any **non-blank** label
whose `LabelValidation.normalize` was already seen earlier in `positives ++ negatives` scan order
(the same order `LabelValidation` scans), then `setLabels(newPos, newNeg)`. This deterministically
clears the duplicate error in one tap regardless of the cap. Blanks are preserved (validation
ignores them). Pure/JVM-testable.

## Data flow

```
RunState.Success → ResultsSection (state keyed on success)
   ├─ BestMatchCard(bestMatch, meta)
   ├─ RankedList(ScoreView.ranked(scores).take(≤MAX_ROWS), thumbnails)   // rows expand by label
   └─ ModeExtras(r) → TemporalExtras (capped, restyled) | ContrastExtras (legacy, post-unlock only)
SetupCard Mode selector ← ScoreView.visibleModes(state.contrastUnlocked, state.mode)
ClassifyViewModel.setMode(TEMPORAL) ×20 → contrastUnlocked
```

## Error / edge cases

- `scores` empty → best-match card only (shouldn't occur post-run).
- `N ≤ COLLAPSED` → no show-more toggle; `N > MAX_ROWS` → top `MAX_ROWS` + truncation note.
- MAX row with `peakFrameIndex` but missing `thumbnails[idx]` → show timestamp only.
- Restored `mode == CONTRAST` with `contrastUnlocked == false` → contrast segment shown (filter
  clause); intended, not a leak.
- TEMPORAL with > cap active labels → top-K rendered + `… N more` notes.

## Testing

- **`ScoreViewTest`** (JVM): `ranked` desc + stable on ties; with a **1000-item fixture**
  `ranked(...).take(MAX_ROWS).size == 50`; `pct` (`0.873→"87.3%"`, `1.0→"100.0%"`),
  `signedCos` (`0.42→"+0.420"`, `-0.05→"-0.050"`), `secs` (`3.15→"3.2 s"`) under a non-US default
  locale (asserts `Locale.US`); `topSummaries` filters inactive (segmentCount 0) and caps by DWC;
  `topActiveLabels` returns active labels by DWC ≤ k and is **empty** when every summary has
  `segmentCount 0` (drives the M2 empty state);
  `visibleModes(false, MEAN)` excludes CONTRAST, `(true, …)` and `(false, CONTRAST)` include it;
  constants (`COLLAPSED 5`, `MAX_ROWS 50`).
- **`ClassifyViewModelTest`** (+cases): 19 temporal taps → locked; 20th → unlocked; non-temporal
  taps don't count; `contrastUnlocked`/`temporalTaps` restored from `SavedStateHandle`;
  `dedupeLabels()` drops the later within-list **and** cross-list (CONTRAST) duplicate, keeps the
  first occurrence + blanks, and clears `validationError`.
- **Manual UAT checklist** (added to the phase report; SAF/run not scriptable — the project's
  established UI-verification path): (a) collapsed list shows 5 + "show more"; (b) expand-all caps
  at 50 with the "Top 50 of N" note on a large CSV; (c) tap row → cosine (+ MAX thumbnail; fallback
  when missing); (d) rerun with reordered labels → no stale expansion on the wrong label; (e) mode
  selector hides CONTRAST, 20 temporal taps reveal it, and selecting CONTRAST + running shows the
  contrast verdict + groups (legacy `ContrastExtras` UX, unchanged); (f) TEMPORAL shows capped
  timeline/segments/summaries (≤6 series + matching bands) with count notes, and an all-below-
  threshold run shows `No segments met threshold`; (g) a 1000-label CSV import leaves the **setup**
  editor responsive — first 50 fields + `+N more imported`, `+ Add label` hidden — and the screen
  scrolls smoothly before and after a run; (h) a 60-label CONTRAST import whose duplicate sits below
  row 50 shows `Duplicate label: …` + a `Remove duplicates` button that clears it in one tap (M1).
  (An on-device Compose smoke suite is optional — deps
  exist — but the bounded-render and unlock logic are pure-tested above.)

## Non-goals (YAGNI)

- Virtualization / `LazyColumn` (render is bounded ≤ `MAX_ROWS`).
- Search/filter/paging across all 1000 labels (top-K + the truncation note is the contract).
  **Accepted tradeoff (L1):** labels ranked below `MAX_ROWS` are not inspectable after a run; if CSV
  users later need to check a specific low-ranked label, add minimal label search as a follow-up.
- The zero-centered cosine bar chart (signal moves to per-row expand).
- Deleting/changing `BarChart`/`ChartData` (still used by Benchmark).
- Permanent (cold-start-surviving) contrast unlock — session/task-scoped only.
- Restyling `ContrastExtras` or making the verdict the primary surface (early-dev; legacy UX kept).
- Any engine/scoring change.
