# ClipCC-Android — CSV label import design

**Date:** 2026-06-29 · **Status:** approved (review-AJ rounds 1–2 incorporated), pre-plan ·
**Branch:** `feat/csv-label-import` off `main`.

## Goal

Let the user populate a label list in the Classify screen by importing a CSV/text file
instead of typing each label by hand. **Each row is exactly one label.**

## Behavior contract

- A per-list **"Import CSV"** button sits beside each label editor. In MEAN/MAX/TEMPORAL there
  is one (Labels). In CONTRAST there are two (Positive labels, Negative labels) — import targets
  the list whose button was pressed.
- Import buttons are **disabled whenever label editing is disabled** (`running == true`), so a
  run's captured labels can never diverge from the visible list.
- Pressing it opens the system document picker filtered to text/CSV. The file is then **read and
  parsed before any confirmation** so the user sees the consequences:
  - **`parsed == 0`** (empty / all-blank / giant-truncated single row): no dialog. Show a notice;
    the list is **never** modified. Replace cannot clear a list via an empty parse.
  - **`parsed > 0`**: a **Replace / Append / Cancel** dialog (decided per-import — no remembered
    preference) showing counts:
    - **Replace** — `"Replace {existing} → {parsed} labels"`. Target list becomes exactly the
      parsed rows.
    - **Append** — `"Append: {new} new, {dup} duplicate"`. Parsed rows not already present (by
      merge identity) are added to the target list.
    - **Cancel** — no change.
  - Truncation / dropped-overlong context is shown in the dialog body and the final notice.
- After import, existing `LabelValidation` runs as today: cross-list duplicates (CONTRAST) and
  empty-list conditions surface through the normal inline validation message.

## Merge identity (shared canonicalization)

Label equality for **append dedup**, **within-file dedup**, and **validation** uses one rule:
`LabelValidation.normalize(s) = s.trim()` — **trim only, case-preserving** (labels are
case-sensitive; lowercasing would break tokenizer parity — load-bearing). `validate()` is
refactored to call `normalize` so import and validation cannot disagree (existing `" car "` and
imported `"car"` are the same label and append skips it).

## Caps

| Cap | Value | On exceed |
|-----|-------|-----------|
| `MAX_BYTES` | 256 KB | reader trims to last line-break, sets `byteTruncated` |
| `MAX_LABELS` | 1000 | `parse` keeps first 1000, sets `labelTruncated` |
| `MAX_LABEL_CHARS` | 256 | `parse` **drops** the overlong row, counts it (`dropped`) |

A picked document is untrusted input. `MAX_LABEL_CHARS` guards Compose/`SavedStateHandle`/native
tokenization against one pathological huge row inside an otherwise-valid <256 KB file (the
tokenizer truncates to 64 tokens regardless, so 256 chars is purely a UI/state-sanity bound).
Overlong rows are dropped, not silently truncated (truncating would change the label's meaning).

## Pure layer (`ui/classify/LabelCsv.kt`) — Android-free, JVM-tested

```
const val MAX_BYTES = 256 * 1024
const val MAX_LABELS = 1000
const val MAX_LABEL_CHARS = 256

data class Read(val text: String, val byteTruncated: Boolean)
fun read(input: InputStream): Read        // throws on I/O error or invalid UTF-8; does NOT close

data class Parsed(val labels: List<String>, val labelTruncated: Boolean, val dropped: Int)
fun parse(text: String): Parsed

data class Merged(val labels: List<String>, val inserted: Int)   // inserted = labels actually added
fun merge(existing: List<String>, parsed: List<String>, replace: Boolean): Merged
```

**`read`** (M2: caller owns the stream — see UI):
1. Read up to `MAX_BYTES + 1` bytes (sentinel detects overflow).
2. `byteTruncated = bytesRead > MAX_BYTES`. If truncated, keep `bytes[0 until MAX_BYTES]` then
   **drop everything after the last line-break byte** (`\n`/`\r`) → decoded text ends on a
   complete row, so the cut is never mid-row, mid-quote, or mid-UTF-8 codepoint. No line break in
   the kept slice (one giant row) → empty text.
3. **Strict UTF-8 decode** (`CharsetDecoder`, `CodingErrorAction.REPORT`). Malformed input throws
   `CharacterCodingException` → import rejected, list unchanged (never persist junk labels).
4. Returns `Read(text, byteTruncated)`. **Does not close `input`** — the caller wraps it in `.use`.

**`parse`** — `each row → one label`, on text that already holds only complete rows:
1. Strip a leading UTF-8 BOM (`U+FEFF`).
2. Split on line breaks (`\r\n`, `\r`, `\n`).
3. Per row: `normalize` (trim); if wrapped in a single pair of double quotes, strip them and
   unescape `""` → `"` (RFC-4180 single-column field). Unbalanced quote left as literal content.
4. Drop rows empty after the above.
5. Drop rows longer than `MAX_LABEL_CHARS`; increment `dropped`.
6. Drop duplicates within the file by merge identity (keep first).
7. Cap at `MAX_LABELS`; set `labelTruncated` if exceeded (keep first `MAX_LABELS`).
8. No comma-splitting — a row is one label even if it contains commas.

**`merge`** — `replace` → `Merged(parsed, parsed.size)`; `append` → existing + (parsed whose
`normalize` is not already a `normalize` of an existing entry), `inserted` = count added.

**Ceiling (documented, not handled):** multi-column CSV, header-row detection, embedded-newline
quoted fields.

## ViewModel (`ClassifyViewModel`) — orchestration, no Android I/O

```
enum class LabelTarget { POSITIVE, NEGATIVE }
fun setLabelList(target: LabelTarget, list: List<String>)
```
Routes the committed list to the correct field — POSITIVE → `setLabels(list, negatives)`,
NEGATIVE → `setLabels(positives, list)` — reusing the existing `setLabels` (persist +
`withDerived` validation). This is the single tested seam for target routing (M3).

## UI (`ui/classify/SetupCard.kt`) — thin Android glue

- Each `EditableList` call gains `target: LabelTarget` + an "Import CSV" button
  (`enabled = !running`). The button's `target` is a static constant per call site (POSITIVE for
  Labels/Positive, NEGATIVE for Negative).
- Button → `rememberLauncherForActivityResult(OpenDocument())` with
  `arrayOf("text/csv", "text/comma-separated-values", "text/plain", "text/*")`.
- On pick, on `Dispatchers.IO`:
  `val read = contentResolver.openInputStream(uri)?.use { LabelCsv.read(it) } ?: error(...)`
  then `LabelCsv.parse(read.text)`. Exceptions / null stream → caught → error notice, list
  unchanged.
- `parsed == 0` → notice only, no dialog. Else compute the append preview via
  `LabelCsv.merge(targetList, parsed.labels, replace = false)` and show the dialog with counts.
- On **Replace** → `vm.setLabelList(target, parsed.labels)`; on **Append** → `vm.setLabelList(
  target, mergedAppend.labels)`; Cancel → nothing. Render the outcome notice.

## Data flow

```
[Import CSV] → OpenDocument → IO: openInputStream?.use { LabelCsv.read } → parse
   ├─ throws/null → "Couldn't read file (not valid text)"; unchanged
   ├─ parsed == 0 → notice (see precedence); unchanged
   └─ parsed > 0 → dialog {Replace {e}→{p} | Append {new}/{dup} | Cancel}
        → vm.setLabelList(target, chosenList) → setLabels → updateSetup → re-render + re-validate
        → outcome notice
```

## Notices (precedence top-down)

1. read failed → `"Couldn't read file (not valid text)"`.
2. `parsed == 0 && (byteTruncated || labelTruncated)` →
   `"No complete labels before the file was truncated"` (L1).
3. `parsed == 0` → `"No labels found in file"`.
4. Replace committed → `"Loaded {parsed} labels"`.
5. Append committed, `inserted > 0` → `"Added {inserted} labels"`
   (+ `", skipped {parsed-inserted} duplicates"` if any).
6. Append committed, `inserted == 0` → `"All {parsed} labels already present"`.
- Any committed notice (4–6) appends `" (file truncated)"` if `byteTruncated || labelTruncated`,
  and `" ({dropped} too long, skipped)"` if `dropped > 0`.

## Testing

- **`LabelCsvTest`** (JVM):
  - `read` via `ByteArrayInputStream`: under-cap passthrough; sentinel overflow sets
    `byteTruncated`; overflow trims to last line-break (mid-line / mid-quote / mid-multibyte all
    yield only whole rows + no decode throw); giant single row → empty text; invalid UTF-8 throws;
    lone-`\r` endings; **does not close** (assert stream still open / a close-tracking stream).
  - `parse`: plain rows; CRLF/`\r`/`\n`; BOM; surrounding quotes + `""` unescape; comma-in-row
    stays one label; blank-row skip; within-file dedup by normalize; `MAX_LABELS` truncation;
    one overlong row dropped (`dropped == 1`) while others kept.
  - `merge`: replace overwrites; append skips dups **including whitespace variants** (`" car "`
    vs `"car"`); `inserted` counts; append into empty existing.
- **`ClassifyViewModelTest`**: `setLabelList(POSITIVE, …)` changes only `positives`;
  `setLabelList(NEGATIVE, …)` changes only `negatives`; persisted state + `validationError`
  reflect the committed list (M3).
- **No device/instrumented test.** The security path (bounded read, overflow, strict decode,
  exceptions, stream non-closing) and all counting/merge/notice logic are pure and JVM-tested. The
  residual Compose surface is the `.use` read call, the static per-call-site `target`, the dialog
  state, and string formatting — no branching logic that isn't already covered by the pure layer.

## Non-goals (YAGNI)

Header-row detection · multi-column mapping · column picker · CSV export · remembered
replace/append preference · embedded-newline quoted fields · best-effort import of non-UTF-8 input ·
a standalone "Clear labels" action (the per-row `×` already clears; empty import never clears).
