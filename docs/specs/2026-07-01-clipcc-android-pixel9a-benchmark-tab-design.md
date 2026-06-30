# ClipCC-Android — Pixel 9a benchmark comparison tab

**Date:** 2026-07-01 · **Status:** design approved, pending spec review
**Goal:** A third tab that shows the Pixel 9a (Tensor G4) benchmark next to the bundled Pixel 7a
(Tensor G2) snapshot, with the **% faster** front and center.

## Context / key finding

The existing "Benchmark" tab is a **read-only** view of `app/src/main/assets/phase2-benchmark-result.json`
— a snapshot captured once from a Pixel 7a. It runs nothing live. "A tab that runs the benchmark on
Pixel 9a" therefore means: feed in a real 9a snapshot and render the per-model speedup.

A real 9a snapshot **already exists**: `docs/plans/spikeA-pixel9a-g4-benchmark-result.json` (Spike A,
2026-06-28). Same schema as the 7a snapshot (`prep` / `runs` / `capabilities`), all 4 models × both CPU
lanes (CPU_XNNPACK, CPU_EP). The spike author already published the G4-vs-G2 comparison on **ms/frame**
(frame-count- and clip-resolution-independent, because vision-encode runs on the fixed 256/384 input).
ORT is 1.26.0 on both (the build pins `onnxruntime-android:1.26.0`; the 7a snapshot does not record its
ORT but there is no evidence of a prior version). Decision: **bundle the spikeA JSON as the 9a asset and
ship now.** The header states the comparison basis honestly (see UI Layout).

The 9a snapshot was captured on a different clip (30 s 640×480, 16 frames; so400m 4) than the 7a
(`test.mp4` 720×1280, 7 frames; so400m 4). This does **not** corrupt the comparison: all math is on
`msPerFrame`, which is per-frame-normalized and dominated by the model forward pass at fixed resolution.
The header discloses both clips.

## Components

| # | File | Type | What |
|---|------|------|------|
| 1 | `app/src/main/assets/phase2-benchmark-result-9a.json` | new asset | Verbatim copy of `docs/plans/spikeA-pixel9a-g4-benchmark-result.json`. Parsed by the existing `BenchmarkData.parse` unchanged. |
| 2 | `app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkCompare.kt` | new, pure JVM | Joins two `List<ModelGroup>` (7a, 9a) into a comparison model. Pure function, no Android deps → JVM-testable. |
| 3 | `app/src/main/java/com/example/clipcc/ui/benchmark/Pixel9aScreen.kt` | new Composable | Hero card + per-model rows. Reads both assets, builds the comparison, renders. Graceful empty state if the 9a asset is absent. |
| 4 | `app/src/main/java/com/example/clipcc/ui/app/ClipCCApp.kt` | 1-line edit | Add third tab `"Pixel 9a"`; route `tab == 2` → `Pixel9aScreen()`. The existing `"Benchmark"` tab (7a raw) is untouched. |
| 5 | `app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkCompareTest.kt` + `app/src/test/resources/phase2-benchmark-result-9a.json` | new test | Pure-JVM test of the comparison math; joins the existing 59-test suite. |

## Comparison math (`BenchmarkCompare.kt`)

Input: `sevenA: List<ModelGroup>`, `nineA: List<ModelGroup>` (both from `BenchmarkData.parse`).

Per model present in **both** snapshots, per backend lane present in **both** (`CPU_XNNPACK`, `CPU_EP`):

```
pctFaster(lane) = (ms7a - ms9a) / ms7a * 100      // ms = TimedRow.msPerFrame, lower is better
speedup(lane)   = ms7a / ms9a                       // e.g. 1.61x, shown alongside the %
```

- **Headline lane per model** = the lane with the lowest `ms9a` (fastest 9a config). The same backend is
  used on the 7a side — never cross-backend. (For all 4 models in the current data the fastest lane is the
  same backend on both devices: base-256/base-384 → CPU_EP, large/so400m → CPU_XNNPACK.)
- **Per-model rows** show every shared lane with its own `pctFaster`, the headline lane bolded.
- **Hero aggregate** = arithmetic mean of each model's headline-lane `pctFaster`. With the current data:
  base-256 +38%, base-384 +31%, large +9%, so400m +8% → **≈ +22%**.

Data classes (sketch):

```kotlin
data class LaneDelta(val backend: String, val ms7a: Double, val ms9a: Double,
                     val pctFaster: Double, val speedup: Double)
data class ModelDelta(val modelId: String, val lanes: List<LaneDelta>, val headline: LaneDelta)
data class Comparison(val models: List<ModelDelta>, val avgPctFaster: Double, val modelCount: Int)

object BenchmarkCompare {
    fun build(sevenA: List<ModelGroup>, nineA: List<ModelGroup>): Comparison
}
```

Edge cases the function handles: a model in one snapshot but not the other → skipped (not in `models`);
a lane in one but not the other → that lane skipped; a model with no shared lanes → skipped; empty
result (no overlap) → `Comparison(emptyList(), 0.0, 0)` → screen shows empty state.

## UI layout (`Pixel9aScreen.kt`)

Scrollable column, matching `BenchmarkScreen`'s Material3 card style:

1. **Header line** (bodySmall, outline color), honest about the basis:
   > Pixel 9a · Tensor G4 vs Pixel 7a · Tensor G2 · CPU-only · ms/frame (vision-encode, frame-count-
   > independent) · ORT 1.26.0 · 9a: 30 s 640×480, 16 frames · 7a: test.mp4 720×1280, 7 frames · so400m 4 ·
   > spikeA 2026-06-28 vs phase-2 2026-06-03
2. **Hero card** (ElevatedCard, prominent): `⚡  +{avgPctFaster, %.0f}% faster`, subtitle
   `average across {modelCount} models · best lane each`.
3. **Per-model cards** (one per `ModelDelta`): title = `modelId`; for each lane a row
   `{backend}: {ms7a, %.0f} → {ms9a, %.0f} ms/frame   +{pctFaster, %.0f}%  ({speedup, %.2f}×)`, headline
   lane bolded / accent-colored.
4. **Empty state** (when `models` is empty or the 9a asset fails to load): a single centered message,
   "Pixel 9a snapshot not bundled." — keeps the build green if the asset is ever missing.

`%`/`×` formatting uses `Locale.US` (consistency with the existing JSON serializer convention).

## Data flow

```
assets/phase2-benchmark-result.json    ─┐
                                        ├─ BenchmarkData.parse ×2 ─→ BenchmarkCompare.build ─→ Pixel9aScreen
assets/phase2-benchmark-result-9a.json ─┘
```

Both reads wrapped so a missing/malformed 9a asset → empty `Comparison` → empty state (try/catch around
the 9a `assets.open`, not a loader abstraction).

## Testing

- **`BenchmarkCompareTest`** (pure JVM, no device): parse the bundled 7a + 9a test-resource JSONs, assert
  per-lane `pctFaster` against hand-computed values (base-256 CPU_EP ≈ 38.1%), assert the hero average
  ≈ 22%, assert headline-lane selection picks the lowest-`ms9a` lane, assert a model present in only one
  snapshot is skipped. Joins the existing suite (target: 60+/0-fail).
- **No device gate needed** — the 9a "run" is the already-completed Spike A; this change is display-only.
- Manual: launch app, confirm the third tab renders the hero + 4 model cards (screenshot).

## Out of scope / deferred

- **Fresh same-clip 9a capture** (re-run `BenchmarkMatrixTest` on the 9a against `test.mp4`/7 frames). The
  spike establishes the story won't change; the tab can swap to a clip-matched JSON later with no code
  change. Commands documented in `phase2-report.md` if needed.
- **Renaming the "Benchmark" tab to "Pixel 7a"** — its `HEADER` already says Pixel 7a; not renaming to keep
  the diff minimal.
- Live on-device benchmarking from the UI (still `am instrument`-only, by Plan-2 design — so400m OOM).
- int8/q4 (Spike B) lanes — not in either snapshot.

## File list (final)

```
A  app/src/main/assets/phase2-benchmark-result-9a.json        (copy of spikeA JSON)
A  app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkCompare.kt
A  app/src/main/java/com/example/clipcc/ui/benchmark/Pixel9aScreen.kt
M  app/src/main/java/com/example/clipcc/ui/app/ClipCCApp.kt   (third tab)
A  app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkCompareTest.kt
A  app/src/test/resources/phase2-benchmark-result-9a.json     (copy for the test)
```
