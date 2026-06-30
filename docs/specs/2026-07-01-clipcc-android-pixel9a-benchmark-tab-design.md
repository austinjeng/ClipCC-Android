# ClipCC-Android — Pixel 9a benchmark comparison tab

**Date:** 2026-07-01 · **Status:** design — revised after review-AJ round 3 (L1 wording / L2 empty-reason / L3 route test)
**Goal:** A third tab that shows the Pixel 9a (Tensor G4) benchmark next to the bundled Pixel 7a
(Tensor G2) snapshot, with the **% faster** front and center — on a **protocol-equivalent, fail-closed,
evidence-backed** basis.

## Context / key finding

The existing "Benchmark" tab is a **read-only** view of `app/src/main/assets/phase2-benchmark-result.json`
— a snapshot captured once from a Pixel 7a. It runs nothing live. "A tab that runs the benchmark on
Pixel 9a" therefore means: feed in a real 9a snapshot and render the per-model speedup.

A real 9a snapshot **already exists**: `docs/plans/spikeA-pixel9a-g4-benchmark-result.json` (Spike A,
2026-06-28). Same benchmark-result **core shape** (`prep`/`runs`/`capabilities`) as the 7a asset, **plus
9a-only provenance fields** (`device`/`profile`/`ort`/`note`) that the 7a asset lacks; all 4 models × both
CPU lanes. ORT 1.26.0 on both. Decision: **bundle the spikeA JSON as the 9a asset and ship now** — no
device re-run.

### Protocol equivalence (review H1) — fail-closed

The two snapshots used **different sampled frame counts**: 7a = 7 (base/large), 9a = 16; both 4 for so400m.
`Benchmark.kt:113` computes `msPerFrame = visionMsMedian / frames`, and **CPU_EP** encodes in chunks of
`visionBatchFor` (`OrtTower.encodeVision:99`, `minOf(batch, frames-f)`) — so for CPU_EP, batch *occupancy*
(not just the denominator) depends on frame count, and `ms/frame` does **not** normalize it away:

| Lane | batch | 7a (7 fr) | 9a (16 fr) | protocol-matched |
|---|---|---|---|---|
| base-256 CPU_EP | 16 | chunk of 7 | chunk of 16 | ❌ |
| base-384 CPU_EP | 16 | chunk of 7 | chunk of 16 | ❌ |
| large CPU_EP | 8 | chunk of 7 | 8 + 8 | ❌ |
| so400m CPU_EP | 4 | chunk of 4 | chunk of 4 | ✅ (4 = 4) |
| **all XNNPACK** | 1 | batch-1/frame | batch-1/frame | ✅ (frame-count-independent) |

**Rule used everywhere (fail-closed):** a lane is *protocol-matched* iff `backend == CPU_XNNPACK` **OR** both
snapshots have a **present and equal** sampled frame count for that model:

```
f7 = meta7a.framesByModel[model]; f9 = meta9a.framesByModel[model]
matched = backend == "CPU_XNNPACK" || (f7 != null && f9 != null && f7 == f9)
```

Missing frame metadata → **not** matched (never `null == null → true`; review H1). Only protocol-matched
lanes feed the headline/hero. Frame counts come from each asset's `prep`, so a future clip-matched 9a re-run
auto-promotes the CPU_EP lanes with **no code change**. XNNPACK `ms/frame` is clip- and frame-count-
independent (independent batch-1 forward passes at fixed 256/384), so the XNNPACK comparison is clean despite
the different clips.

### Delta robustness (review M2) — measured, not asserted

There is no fixed "noise threshold." Each snapshot run carries `visionMsMin`/`visionMsMax` (3-run spread).
A delta is **band-separated** (robust) iff the two devices' per-frame ranges don't overlap:
`ms9aMax < ms7aMin` (for a speedup). Measured for the hero lanes:

| Hero lane (XNNPACK) | 7a med [min–max] | 9a med [min–max] | faster | band-separated |
|---|---|---|---|---|
| base-256 | 2372 [2365–2373] | 1491 [1469–1499] | +37% | ✅ |
| base-384 | 5520 [5426–5645] | 3474 [3470–3479] | +37% | ✅ |
| large | 10678 [10669–10714] | 9686 [9218–9739] | +9% | ✅ (9739 < 10669) |
| so400m | 17880 [17822–17922] | 16384 [16280–16536] | +8% | ✅ (16536 < 17822) |

All four hero deltas exceed their measured 3-run spread → robust within this capture. (The noisiest lane is
so400m **CPU_EP** at 15.8% spread — a non-hero confounded lane; its noise is extra reason it stays out of the
hero.) The UI shows each lane's `median (min–max)` and labels a non-band-separated delta "≈ (within 3-run
spread)". **Caveat displayed:** Spike A was not a sustained-load test, so band-separation bounds 3-burst
repeatability, not thermal drift.

## Components

| # | File | Type | What |
|---|------|------|------|
| 1 | `app/src/main/assets/phase2-benchmark-result-9a.json` | new asset | Verbatim copy of the spikeA JSON. |
| 2 | `BenchmarkData.kt` | **edit** | (a) `TimedRow` gains `msPerFrameMin`/`Max` (= `visionMsMin/Max ÷ frames`). (b) `SnapshotMeta` + `parseMeta(json)` (device/soc/ort/note + per-model frames from `prep`). Existing `parse` otherwise unchanged. |
| 3 | `BenchmarkCompare.kt` | new, pure JVM | Joins two `(List<ModelGroup>, SnapshotMeta)` → `Comparison`; fail-closed protocol-match; per-lane deltas, band-separation, hero. |
| 4 | `Pixel9aScreen.kt` | new Composable | Asset-derived header, hero (with per-model range), protocol-matched rows w/ measured spread, caveated non-matched section, empty state. |
| 5 | `ClipCCApp.kt` + `app/build.gradle.kts` | edit | `enum class AppTab(val title)` {CLASSIFY, BENCHMARK, PIXEL9A}; `TabRow` + an **exhaustive** `when(AppTab.entries[tab])`. 7a Benchmark tab untouched. Build: wire `debugImplementation(libs.androidx.compose.ui.test.manifest)` for the route test. |
| 6 | `BenchmarkCompareTest.kt`, `BenchmarkDataMetaTest.kt`, `AppTabTest.kt` + test-resource 9a JSON | new tests (JVM) | Pure-JVM; join the existing 59-test suite. |
| 7 | `ClipCCAppRouteTest.kt` | new test (instrumented) | One Compose route assertion (tab 2 → Pixel9a content). |

New `.kt` UI files live in `ui/benchmark/`; the enum + tab in `ui/app/`. Tests under `app/src/test/java/...`.

## Metadata parse (`SnapshotMeta`) — review M1

```kotlin
data class SnapshotMeta(
    val deviceModel: String?, val soc: String?, val ort: String?, val note: String?,
    val framesByModel: Map<String, Int>,   // from prep[].frames — drives protocol-match
)
fun parseMeta(json: String): SnapshotMeta   // tolerant: optString/optInt, missing → null / absent key
```

The 7a asset has no `device`/`ort`/`note` keys → those parse as `null`; the screen renders a labeled known
constant ("Tensor G2") for the 7a identity and shows `framesByModel` from each asset's `prep` — so the header
**displays the 7-vs-16 frame gap from the assets themselves**. **No capture-date text** in the header: the
assets carry no date field (the 9a `note` is free-text), so the design does not promise asset-derived dates
(review M1). Adding a structured `capturedDate` to the bundled assets is deferred.

## Comparison math (`BenchmarkCompare.kt`)

```kotlin
data class LaneDelta(
    val backend: String,
    val ms7a: Double, val ms7aMin: Double, val ms7aMax: Double,
    val ms9a: Double, val ms9aMin: Double, val ms9aMax: Double,
    val pctFaster: Double, val speedup: Double,
    val protocolMatched: Boolean, val bandSeparated: Boolean,   // ms9aMax < ms7aMin
)
data class ModelDelta(val modelId: String, val lanes: List<LaneDelta>, val headline: LaneDelta?)
data class Comparison(val models: List<ModelDelta>, val avgPctFaster: Double,
                      val minPctFaster: Double, val maxPctFaster: Double, val heroLaneCount: Int)

object BenchmarkCompare {
    fun build(sevenA: Pair<List<ModelGroup>, SnapshotMeta>,
              nineA:   Pair<List<ModelGroup>, SnapshotMeta>): Comparison
}
```

Per model in **both** snapshots, per backend lane in **both**:

```
pctFaster = (ms7a - ms9a) / ms7a * 100        // ms = TimedRow.msPerFrame
speedup   = ms7a / ms9a
protocolMatched = backend == "CPU_XNNPACK" || (f7 != null && f9 != null && f7 == f9)   // fail-closed
bandSeparated   = ms9aMax < ms7aMin
```

- **Headline lane** per model = the **protocol-matched** lane with the lowest `ms9a` (null if none).
- **Hero** = mean of per-model headline `pctFaster` over models with a headline (`heroLaneCount`);
  `min/maxPctFaster` = range of those headline deltas. Current data: base-256 +37%, base-384 +37%, large
  +9%, so400m +8% (all XNNPACK, all band-separated) → **avg ≈ +23%, range +8%–37%**.
- Edge cases: model/lane in one snapshot only → skipped; no protocol-matched lane → `headline = null`
  (model still listed, lanes shown, excluded from hero); no overlap → `Comparison(emptyList(),0,0,0,0)`.

## UI layout (`Pixel9aScreen.kt`)

Scrollable column, Material3 cards matching `BenchmarkScreen`:

1. **Header** (bodySmall, outline), rendered **only from fields present in the assets**:
   `Pixel 9a · {soc9a} vs Pixel 7a · {soc7a} · CPU-only · ms/frame (vision-encode) · ORT {ort9a} ·
   frames/model: 9a {n} / 7a {n}`. (7a `soc` = labeled constant "Tensor G2"; no date.)
2. **Hero card** (ElevatedCard, prominent): `⚡ +{avgPctFaster|%.0f}% faster`, subtitle
   `protocol-matched (XNNPACK) · avg over {heroLaneCount} models · per-model +{min|%.0f}–{max|%.0f}%`.
3. **Per-model cards**: title = `modelId`. Each lane shows **raw** `{ms7a|%.0f} → {ms9a|%.0f} ms/frame`
   with each device's `(min–max)` spread, and `+{pct}% ({speedup|%.2f}×)`. A **non-band-separated** delta
   renders `≈ +{pct}% (within 3-run spread)`. **Non-protocol-matched** lanes (base/large CPU_EP) sit under a
   `— not protocol-matched (7 vs 16 frames) —` subheading, never bolded, never in the hero. Headline lane
   bolded/accented.
4. **Empty state**: centered, message **keyed to the actual failure** (review L2) — not a fixed
   "9a not bundled" for every case (which would misdiagnose a 7a problem).

Formatting uses `Locale.US`.

## Asset-loading contract — review M2 (r1) / L2 (r3)

`load()` returns either a `Comparison` or an `EmptyReason` (`NO_9A` / `NO_7A` / `NO_OVERLAP`), and the empty
state renders a message per reason:

| Case | Result | Empty-state message |
|---|---|---|
| both load, overlap ≥ 1 model | `Comparison` | — (renders comparison) |
| 9a missing/malformed | `NO_9A` | "Pixel 9a snapshot not bundled." |
| 7a missing/malformed | `NO_7A` | "Benchmark (Pixel 7a) snapshot unavailable." |
| both load, no overlapping model | `NO_OVERLAP` | "No models common to both snapshots." |

Implementation: `runCatching { parse + parseMeta }` per asset in the screen's `remember`; 9a failure → `NO_9A`,
7a failure → `NO_7A`, empty overlap → `NO_OVERLAP`. The existing `BenchmarkScreen` (7a tab) is **not** modified.

## Data flow

```
assets/phase2-benchmark-result.json    ─ parse+parseMeta ─┐
                                                          ├─ BenchmarkCompare.build ─→ Pixel9aScreen
assets/phase2-benchmark-result-9a.json ─ parse+parseMeta ─┘   (runCatching; failure → empty state)
```

## Testing

- **`BenchmarkCompareTest`** (pure JVM):
  - per-lane `pctFaster` vs hand-computed (base-256 XNNPACK ≈ 37.1%); hero ≈ 23%, range +8%–37%.
  - headline excludes non-matched CPU_EP base/large; so400m CPU_EP flagged protocol-matched.
  - **fail-closed (H1):** synthetic metas with `prep` missing on both sides → CPU_EP `protocolMatched=false`;
    frame entry missing on one side → false; present-and-equal → true.
  - **band-separation (M2):** all 4 hero lanes `bandSeparated=true`; a synthetic overlapping-band lane → false.
  - model-in-one-snapshot skipped; no-overlap → empty `Comparison`.
- **`BenchmarkDataMetaTest`** (pure JVM): `parseMeta` returns `framesByModel` {base/large 7 (7a) vs 16 (9a),
  so400m 4}; 9a `soc=="Google Tensor G4"`, `ort=="1.26.0"`; 7a device/ort `null`. `TimedRow.msPerFrameMin/Max`
  equal `visionMsMin/Max ÷ frames`.
- **`AppTabTest`** (pure JVM): `AppTab.entries.map { it.title } == ["Classify","Benchmark","Pixel 9a"]`
  and `AppTab.PIXEL9A.ordinal == 2` — pins the index→identity ordering the exhaustive `when` consumes.
- **`ClipCCAppRouteTest`** (instrumented Compose, review L3): `createComposeRule`, set tab index 2, assert a
  **Pixel9a-specific** string renders (the hero "% faster" / "protocol-matched") and a 7a-only string
  (`BenchmarkScreen`'s "median-of-3" header) does **not** — closes the "wrong `when` body renders the wrong
  screen" gap that a title-only test cannot. Reuses the existing `androidx.compose.ui.test.junit4` androidTest
  dep; needs `debugImplementation(libs.androidx.compose.ui.test.manifest)` wired (catalog entry already
  exists). Emulator-only — no models/clip needed.
- **No data-capture gate** — display-only over completed Spike A data; the route test runs on a bare
  emulator. Manual: launch, screenshot the third tab.

## Out of scope / deferred

- Clip-matched 9a re-run (test.mp4 / 7 frames) — auto-promotes CPU_EP base/large into the hero with no code
  change (protocol-match is data-driven). Spike A says the story won't change. Commands in `phase2-report.md`.
- Structured `capturedDate` in bundled assets (M1 follow-up).
- Renaming "Benchmark" → "Pixel 7a"; live on-device benchmarking (still `am instrument`-only); int8/q4.

## File list (final)

```
A  app/src/main/assets/phase2-benchmark-result-9a.json                 (copy of spikeA JSON)
M  app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkData.kt   (TimedRow min/max + SnapshotMeta/parseMeta)
A  app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkCompare.kt
A  app/src/main/java/com/example/clipcc/ui/benchmark/Pixel9aScreen.kt
M  app/src/main/java/com/example/clipcc/ui/app/ClipCCApp.kt            (AppTab enum + exhaustive route)
M  app/build.gradle.kts                                                (debugImplementation ui-test-manifest)
A  app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkCompareTest.kt
A  app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkDataMetaTest.kt
A  app/src/test/java/com/example/clipcc/ui/app/AppTabTest.kt
A  app/src/androidTest/java/com/example/clipcc/ui/app/ClipCCAppRouteTest.kt   (instrumented route assertion)
A  app/src/test/resources/phase2-benchmark-result-9a.json              (copy for tests)
```
