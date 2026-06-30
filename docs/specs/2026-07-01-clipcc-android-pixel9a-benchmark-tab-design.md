# ClipCC-Android — Pixel 9a benchmark comparison tab

**Date:** 2026-07-01 · **Status:** design — revised after review-AJ (H1/M1/M2/M3/L1)
**Goal:** A third tab that shows the Pixel 9a (Tensor G4) benchmark next to the bundled Pixel 7a
(Tensor G2) snapshot, with the **% faster** front and center — on a **protocol-equivalent** basis.

## Context / key finding

The existing "Benchmark" tab is a **read-only** view of `app/src/main/assets/phase2-benchmark-result.json`
— a snapshot captured once from a Pixel 7a. It runs nothing live. "A tab that runs the benchmark on
Pixel 9a" therefore means: feed in a real 9a snapshot and render the per-model speedup.

A real 9a snapshot **already exists**: `docs/plans/spikeA-pixel9a-g4-benchmark-result.json` (Spike A,
2026-06-28). Same schema (`device`/`profile`/`ort`/`note`/`prep`/`runs`/`capabilities`), all 4 models ×
both CPU lanes. ORT 1.26.0 on both (build pins `onnxruntime-android:1.26.0`; the 7a snapshot predates the
metadata field but there is no evidence of a prior version). Decision: **bundle the spikeA JSON as the 9a
asset and ship now** — no device re-run.

### Protocol equivalence (review finding H1 — load-bearing)

The two snapshots used **different sampled frame counts**: 7a = 7 frames (base/large), 9a = 16 frames; both
use 4 for so400m. `Benchmark.kt:113` computes `msPerFrame = visionMsMedian / frames`, and CPU_EP encodes in
chunks of `visionBatchFor` (`OrtTower.encodeVision:99`, `minOf(batch, frames-f)`). So for **CPU_EP**, batch
*occupancy* — not just the denominator — depends on frame count, and `ms/frame` does **not** normalize it
away:

| Lane | batch | 7a (7 fr) | 9a (16 fr) | protocol-matched |
|---|---|---|---|---|
| base-256 CPU_EP | 16 | 1 chunk of 7 | 1 chunk of 16 | ❌ |
| base-384 CPU_EP | 16 | chunk of 7 | chunk of 16 | ❌ |
| large CPU_EP | 8 | chunk of 7 | 8 + 8 | ❌ |
| so400m CPU_EP | 4 | chunk of 4 | chunk of 4 | ✅ (4 = 4) |
| **all XNNPACK** | 1 | batch-1/frame | batch-1/frame | ✅ (frame-count-independent) |

**Rule used everywhere in this design:** a lane is *protocol-matched* iff `backend == CPU_XNNPACK`
**OR** the two snapshots sampled the **same frame count** for that model. Only protocol-matched lanes feed
the headline/hero. This is computed from the data (frame counts come from each asset's `prep`), so a future
clip-matched 9a re-run auto-promotes the CPU_EP lanes into the hero with **no code change**.

`ms/frame` for XNNPACK *is* clip- and frame-count-independent (each frame is an independent batch-1 forward
pass at fixed 256/384 resolution), so the XNNPACK comparison is clean despite the different clips.

## Components

| # | File | Type | What |
|---|------|------|------|
| 1 | `app/src/main/assets/phase2-benchmark-result-9a.json` | new asset | Verbatim copy of `docs/plans/spikeA-pixel9a-g4-benchmark-result.json`. |
| 2 | `BenchmarkData.kt` | **edit** | Add `SnapshotMeta` + `parseMeta(json)` (device/soc/ort/note + per-model frame counts from `prep`). Existing `parse` unchanged. |
| 3 | `BenchmarkCompare.kt` | new, pure JVM | Joins two `(List<ModelGroup>, SnapshotMeta)` into a `Comparison`; determines protocol-match per lane; computes deltas + hero. JVM-testable. |
| 4 | `Pixel9aScreen.kt` | new Composable | Asset-derived header, hero, protocol-matched rows, a caveated "not protocol-matched" section, empty state. |
| 5 | `ClipCCApp.kt` | edit | Extract `tabTitles` constant (3 entries); route `tab == 2` → `Pixel9aScreen()` via `when`. 7a "Benchmark" tab untouched. |
| 6 | `BenchmarkCompareTest.kt`, `BenchmarkDataMetaTest.kt`, `TabTitlesTest.kt` + test-resource 9a JSON | new tests | Pure-JVM; join the existing 59-test suite. |

(All new `.kt` files live in `app/src/main/java/com/example/clipcc/ui/benchmark/` except the tab edit;
tests under `app/src/test/java/com/example/clipcc/ui/...`.)

## Metadata parse (`SnapshotMeta`) — review finding M1

`BenchmarkData.parse` returns only model/timed/capability rows, dropping provenance. Add:

```kotlin
data class SnapshotMeta(
    val deviceModel: String?, val soc: String?, val ort: String?, val note: String?,
    val framesByModel: Map<String, Int>,   // from prep[].frames — drives protocol-match
)
fun parseMeta(json: String): SnapshotMeta   // tolerant: every field optDouble/optString, missing → null/empty
```

The 7a asset has no `device`/`ort`/`note` keys → those parse as `null`; the screen renders a labeled
constant ("Pixel 7a · Tensor G2") for the known-bundled 7a identity and shows `framesByModel` (7) from its
`prep`. The header thus **displays the 7-vs-16 frame difference from the assets themselves** — no hardcoded
clip facts that can drift on an asset swap.

## Comparison math (`BenchmarkCompare.kt`)

```kotlin
data class LaneDelta(val backend: String, val ms7a: Double, val ms9a: Double,
                     val pctFaster: Double, val speedup: Double,
                     val protocolMatched: Boolean, val marginal: Boolean)   // marginal = |pct| < NOISE
data class ModelDelta(val modelId: String, val lanes: List<LaneDelta>, val headline: LaneDelta?)
data class Comparison(val models: List<ModelDelta>, val avgPctFaster: Double, val heroLaneCount: Int)

object BenchmarkCompare {
    const val NOISE_PCT = 15.0   // below this, single-shot deltas are within run-to-run variance (M3)
    fun build(sevenA: Pair<List<ModelGroup>, SnapshotMeta>,
              nineA:   Pair<List<ModelGroup>, SnapshotMeta>): Comparison
}
```

Per model in **both** snapshots, per backend lane in **both**:

```
pctFaster = (ms7a - ms9a) / ms7a * 100        // ms = TimedRow.msPerFrame
speedup   = ms7a / ms9a
protocolMatched = backend == "CPU_XNNPACK" || frames7a[model] == frames9a[model]
marginal  = abs(pctFaster) < NOISE_PCT
```

- **Headline lane** per model = the **protocol-matched** lane with the lowest `ms9a` (null if none).
- **Hero** = mean of the per-model headline `pctFaster` over models that have a headline lane
  (`heroLaneCount`). With current data: base-256 +37%, base-384 +37%, large +9%, so400m +8% (all XNNPACK)
  → **≈ +23%**.
- Edge cases: model in one snapshot only → skipped; lane in one only → skipped; no protocol-matched lane
  → `headline = null`, model still listed (lanes shown, excluded from hero); no overlap at all →
  `Comparison(emptyList(), 0.0, 0)` → empty state.

## UI layout (`Pixel9aScreen.kt`)

Scrollable column, Material3 card style matching `BenchmarkScreen`:

1. **Header** (bodySmall, outline), asset-derived: `Pixel 9a · {soc9a} vs Pixel 7a · {soc7a} · CPU-only ·
   ms/frame (vision-encode) · ORT {ort} · frames: 9a {n} / 7a {n} per model · spikeA {date} vs phase-2`.
2. **Hero card** (ElevatedCard, prominent): `⚡ +{avgPctFaster|%.0f}% faster`, subtitle
   `protocol-matched (XNNPACK) · avg over {heroLaneCount} models`.
3. **Per-model cards**: title = `modelId`. Raw **ms/frame shown prominently** for both devices. Each lane:
   `{backend}: {ms7a|%.0f} → {ms9a|%.0f} ms/frame  {±pct}` where a **marginal** delta renders as
   `≈ +{pct}% (bandwidth-bound, within run-to-run variance)` and a non-matched lane is grouped under a
   `— not protocol-matched (7 vs 16 frames) —` subheading and never bolded. Headline lane bolded/accented.
4. **Empty state**: centered "Pixel 9a snapshot not bundled." (see contract below).

Formatting uses `Locale.US`.

## Asset-loading contract — review finding M2

One contract for the new screen, four outcomes; `BenchmarkData.parse`/`parseMeta` throw on malformed JSON,
so both loads are wrapped:

| Case | Behavior |
|---|---|
| both load, overlap ≥ 1 model | render comparison |
| 9a asset missing or malformed | empty state ("Pixel 9a snapshot not bundled") |
| 7a asset missing or malformed | empty state (defensive; 7a is always bundled in practice) |
| both load, no overlapping model | empty state |

Implementation: `runCatching { parse+parseMeta }` per asset in the screen's `remember`; any failure or empty
overlap → empty state. The existing `BenchmarkScreen` (7a tab) is **not** modified.

## Data flow

```
assets/phase2-benchmark-result.json    ─ parse+parseMeta ─┐
                                                          ├─ BenchmarkCompare.build ─→ Pixel9aScreen
assets/phase2-benchmark-result-9a.json ─ parse+parseMeta ─┘   (runCatching; failure → empty state)
```

## Testing

- **`BenchmarkCompareTest`** (pure JVM): per-lane `pctFaster` vs hand-computed (base-256 XNNPACK ≈ 37.1%);
  hero ≈ 23% over 4 headline lanes; headline excludes non-matched CPU_EP base/large; so400m CPU_EP flagged
  protocol-matched; `marginal` true for large/so400m (<15%), false for base; model-in-one-snapshot skipped;
  no-overlap → empty `Comparison`.
- **`BenchmarkDataMetaTest`** (pure JVM): `parseMeta` returns `framesByModel` {base/large = 7 (7a), 16 (9a);
  so400m 4}; 9a `soc == "Google Tensor G4"`, `ort == "1.26.0"`; 7a device/ort `null`.
- **`TabTitlesTest`** (pure JVM, L1): asserts `tabTitles == ["Classify","Benchmark","Pixel 9a"]`.
- **No device gate** — the 9a "run" is the completed Spike A; this change is display-only. Manual: launch,
  confirm the third tab renders hero + cards (screenshot).

## Out of scope / deferred

- **Clip-matched 9a re-run** (test.mp4 / 7 frames) — would promote CPU_EP base/large into the hero with no
  code change (protocol-match is data-driven). Spike A says the story won't change. Commands in `phase2-report.md`.
- Renaming "Benchmark" → "Pixel 7a" (its `HEADER` already says Pixel 7a).
- Live on-device benchmarking from the UI (still `am instrument`-only — so400m OOM, Plan-2 design).
- int8/q4 (Spike B) lanes.

## File list (final)

```
A  app/src/main/assets/phase2-benchmark-result-9a.json                 (copy of spikeA JSON)
M  app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkData.kt   (SnapshotMeta + parseMeta)
A  app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkCompare.kt
A  app/src/main/java/com/example/clipcc/ui/benchmark/Pixel9aScreen.kt
M  app/src/main/java/com/example/clipcc/ui/app/ClipCCApp.kt            (tabTitles + third route)
A  app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkCompareTest.kt
A  app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkDataMetaTest.kt
A  app/src/test/java/com/example/clipcc/ui/app/TabTitlesTest.kt
A  app/src/test/resources/phase2-benchmark-result-9a.json              (copy for tests)
```
