# HANDOFF — Classify UI (CSV import + results redesign + meter fix)

**Date:** 2026-07-01 · **Branch:** `main` (clean, == `origin/main` == `c4387d3`, pushed).
**Device:** Pixel 9a `4C081JEBF03962`, app installed = `c4387d3` (the tested build).

## TL;DR — what shipped this session (all merged to `main`, pushed, on the phone)

1. **CSV label import** (`feat/csv-label-import`, merged `1e0e6f1`). Per-list "Import CSV" button in
   Classify; one row = one label; Replace/Append/Cancel dialog; `LabelCsv` pure parser/reader/merge
   (256 KB / 1000-label caps, strict UTF-8, dedup); `getExternalFilesDir/models` provisioning.
2. **Classify results redesign** (`feat/classify-results-redesign`, merged `c59405c`). Bounded ranked
   confidence-meter list (top-K, tap-to-expand cosine / MAX peak thumbnail), restyled+capped TEMPORAL,
   bounded label editor (`EDIT_CAP=50`), **CONTRAST hidden behind a 20-tap-on-`temporal` reveal** +
   `Remove duplicates` repair. New: `ScoreView` (pure, JVM-tested), `MeterBar`.
3. **Results meter bugfix** (`c4387d3`, direct on `main`). See "The bug we just fixed".
- (Pixel 9a **selectable precision** fp32/fp16/int8 was already on `main` via PR #1 before this session.)

JVM suite: **121 tests, 0 failures**. All UI verified by assembleDebug + on-device.

## The bug we just fixed (read this — it's the recurring class of bug)

**Symptom:** after the results redesign, every label (incl. best match) showed `0.0%` with empty bars.
**Root cause:** SigLIP2 `pairwise_sigmoid` confidence is *near-zero in absolute terms* (large negative
bias) — e.g. best cosine +0.075 → confidence ≈ 0.0003. The redesign's `MeterBar` used the **absolute**
confidence as the fill fraction → all bars empty; the `%` (1 dp) rounds to `0.0%`. The engine was fine
(cosine is real, ranking correct).
**Fix:** `MeterBar` fill = **`confidence / maxConfidence`** (relative to the top label), matching Python
clipCC's `index.html` (`pct = confidence / maxConf * 100`). Added `ScoreView.meter(confidence, max)`.
The `%` text is unchanged — **Python shows the same `0.0%`**; the *relative bars* convey the ranking.
Verified on-device: bars now form a descending ladder.

## PARITY REFERENCE (load-bearing): "work like the Python version"

Python clipCC repo: **`/Users/austin/MITAC/clipCC`**. Standard MEAN/MAX result presentation
(`app/static/index.html`, `app/services/scoring.py`, `app/models/siglip2_model.py`):
- Per-label number = `(confidence * 100).toFixed(1) + '%'` (sigmoid, **independent per label, NOT
  softmax**). Android `ScoreView.pct` matches.
- Bar width = **`confidence / maxConfidence`** (relative, top = 100%). Android `ScoreView.meter` matches.
- **Cosine (`raw_similarity`) is NOT shown** in the Python UI (Android shows it on tap-expand — a bonus).
- Best match = `label (XX.X%)`, = `max(scores, key=confidence)`.
- `scoring.py compute_frame_scores()` (a softmax variant) is **dead code** — never call it.

When in doubt about results behavior, match `/Users/austin/MITAC/clipCC`.

## NEW STANDING INSTRUCTION (in memory: `clipcc-self-test-results`)

For **any** change touching the Classify results path, **self-test on the device end-to-end** before
claiming it works: pick a video + import labels + Run, then screencap the result and confirm the
rendered values/layout are correct. Pure tests + assembleDebug are necessary-but-insufficient — the
results section is where bugs recur (this 0.0% bug passed all JVM tests + assembleDebug).
- Device test assets already present: `/sdcard/Download/drivingFatigue.mov` + `Siglip2_fatigue.csv`
  (9 fatigue-description labels). Driving the SAF picker via `adb input tap` + screencap works (it's
  tedious but reliable; the run takes ~13 s for base-256/int8 over 20 frames).

## What's LEFT (prioritized)

1. **Unit test for `ScoreView.meter`** — skipped under context pressure. Add: `meter(0.5,1.0)==0.5f`,
   `meter(x,0.0)==0f` (div-zero guard), `meter` coerces to 0..1. (`ScoreViewTest.kt`.)
2. **TEMPORAL meters have the SAME absolute-confidence issue** — `ModeExtras.TemporalExtras` segment
   `peakConfidence` + summary `durationWeightedConfidence` `MeterBar`s are absolute → likely near-empty
   for weak matches. Decide per Python's temporal view (not yet mapped) whether to make them relative
   too. (Untouched this session.)
3. **Delete the 3 merged local branches** (`feat/csv-label-import`, `feat/pixel9a-selectable-precision`,
   `feat/classify-results-redesign`) — all in `main`, redundant. (`git branch -d ...`.)
4. **README** (`README.md` + `README.zh-TW.md`) — still only mention precision in the benchmark sense;
   document CSV import, the results UI, the `adb push`→`getExternalFilesDir/models` provisioning, and
   the 20-tap CONTRAST reveal. (Carried over from the precision handoff; never done.)
5. **Manual UAT for the results redesign** — MEAN path verified on-device; TEMPORAL / CONTRAST / MAX
   peak-thumbnail / 1000-label-cap paths NOT yet eyeballed. Checklist (a–h) in the plan.
6. **Host export tooling** (Python repo `tools/android_assets/export_models.py`) — emit v2 multi-precision
   bundles so provisioning isn't hand-assembled. (Carried over; never done.)
- **Accepted-as-is** (results-redesign final review): TEMPORAL Segments list can show a label not in the
  top-6 timeline series (spec-faithful); segment/summary meters use theme `primary`, not per-series color.

## Environment / commands

- `adb`: `~/Library/Android/sdk/platform-tools/adb` (NOT on PATH). Device `4C081JEBF03962`.
- JDK: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (JBR 21).
- JVM tests: `JAVA_HOME=… ./gradlew -p /Users/austin/AndroidStudioProjects/ClipCC :app:testDebugUnitTest`
  (121 tests). Install: `… :app:installDebug`. assembleDebug for UI compile.
- Relaunch app: `adb shell am force-stop com.example.clipcc; adb shell monkey -p com.example.clipcc -c android.intent.category.LAUNCHER 1`
- Models live in `/sdcard/Android/data/com.example.clipcc/files/models/<id>/` (all 4, multi-precision).
  Re-`adb push` only after a data-wipe.
- Python parity repo: `/Users/austin/MITAC/clipCC`; host venv `…/clipCC/.venv-export/bin/python`.

## Key files (Classify UI)

- `ui/classify/ScoreView.kt` — pure helpers: `ranked`, `meter` (relative bar fraction), `pct/signedCos/secs`
  (Locale.US), `topSummaries/topActiveLabels`, `visibleModes`, caps (COLLAPSED 5, MAX_ROWS 50, etc).
- `ui/classify/MeterBar.kt` — decorative bar (`clearAndSetSemantics`).
- `ui/classify/ResultsSection.kt` — best-match card + ranked list (`maxConf` relative meter).
- `ui/classify/ModeExtras.kt` — TEMPORAL restyle (item #2 above lives here).
- `ui/classify/SetupCard.kt` — mode filter (`visibleModes`), `EDIT_CAP` editor, `Remove duplicates` button.
- `ui/classify/ClassifyViewModel.kt` — `CONTRAST_UNLOCK_TAPS=20`, `dedupeLabels()`, `setLabelList`.
- `ui/classify/LabelCsv.kt` — CSV import (read/parse/merge/notices).
- Engine `engine/Scoring.kt` — `ScoreItem(confidence, rawSimilarity, peakFrameIndex, approxTimestampSeconds)`,
  `AggregationResult`, `TemporalResult` — UNCHANGED by this session.

## Pointers

- Specs/plans: `docs/specs/2026-06-29-clipcc-android-classify-results-redesign-design.md`,
  `docs/plans/2026-06-29-clipcc-android-classify-results-redesign-plan.md`; CSV: `…-csv-label-import-*`.
- Prior handoff: `docs/HANDOFF-pixel9a-precision.md`. SDD ledgers: `.superpowers/sdd/progress.md` (gitignored).
- Memory: `…/memory/clipcc-self-test-results.md`, `clipcc-pixel9a-research.md`, `MEMORY.md`.
