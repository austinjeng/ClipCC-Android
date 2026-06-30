# Pixel 9a Benchmark Comparison Tab — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third "Pixel 9a" tab that renders the already-captured Spike-A Pixel 9a (Tensor G4) benchmark against the bundled Pixel 7a (Tensor G2) snapshot, headlining the protocol-matched % faster.

**Architecture:** Display-only over two static JSON assets. A pure-JVM `BenchmarkCompare` joins the two parsed snapshots into a `Comparison` (fail-closed protocol-match + measured band-separation), and a Compose `Pixel9aScreen` renders it. The existing `Benchmark` tab and `BenchmarkData.parse` are untouched except for additive fields. No device data-capture; the 9a "run" is the completed Spike A.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `org.json` (parsing), JUnit4 (pure-JVM unit tests + one Compose instrumented test).

## Global Constraints

- **Fail-closed protocol-match (verbatim):** `backend == "CPU_XNNPACK" || (f7 != null && f9 != null && f7 == f9)`. Missing frame metadata MUST NOT match CPU_EP.
- **Band-separation, measured (no fixed threshold):** `bandSeparated = ms9aMax < ms7aMin`. There is no "noise threshold" constant.
- **All float / percent / × formatting uses `java.util.Locale.US`.**
- **Additive only:** do not change `BenchmarkData.parse`'s existing return values or `BenchmarkScreen`; only add new fields/functions/files.
- **Pure-JVM tests** read fixtures via `javaClass.classLoader!!.getResourceAsStream(name)` and use the existing `org.json` `testImplementation` stub.
- **Hero = protocol-matched headline lanes only.** Current data → all four headlines are `CPU_XNNPACK`, avg ≈ +23%, range +8%–37%.

---

### Task 1: Bundle the Spike-A 9a snapshot as an app asset + test fixture

**Files:**
- Create: `app/src/main/assets/phase2-benchmark-result-9a.json` (copy)
- Create: `app/src/test/resources/phase2-benchmark-result-9a.json` (copy)
- Test: `app/src/test/java/com/example/clipcc/ui/benchmark/Benchmark9aAssetTest.kt`

**Interfaces:**
- Consumes: existing `BenchmarkData.parse(json): List<ModelGroup>` (unchanged).
- Produces: the bundled 9a asset + test fixture that all later tasks read.

- [ ] **Step 1: Copy the spikeA JSON into the asset and test-resource paths**

```bash
cp docs/plans/spikeA-pixel9a-g4-benchmark-result.json app/src/main/assets/phase2-benchmark-result-9a.json
cp docs/plans/spikeA-pixel9a-g4-benchmark-result.json app/src/test/resources/phase2-benchmark-result-9a.json
```

- [ ] **Step 2: Write the failing test** — `Benchmark9aAssetTest.kt`

```kotlin
package com.example.clipcc.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Benchmark9aAssetTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test fun parse9aAsset_has4ModelsWithBothCpuLanes() {
        val groups = BenchmarkData.parse(res("phase2-benchmark-result-9a.json"))
        assertEquals(4, groups.size)
        groups.forEach { g ->
            val backends = g.timed.map { it.backend }.toSet()
            assertTrue("${g.modelId} missing CPU lanes: $backends",
                backends.containsAll(listOf("CPU_XNNPACK", "CPU_EP")))
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it passes** (the asset already exists post-copy)

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.clipcc.ui.benchmark.Benchmark9aAssetTest"`
Expected: PASS (4 model groups, both CPU lanes each).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/phase2-benchmark-result-9a.json app/src/test/resources/phase2-benchmark-result-9a.json app/src/test/java/com/example/clipcc/ui/benchmark/Benchmark9aAssetTest.kt
git commit -m "feat: bundle Spike-A Pixel 9a benchmark snapshot as asset + fixture"
```

---

### Task 2: `BenchmarkData` extensions — per-frame min/max + `SnapshotMeta`/`parseMeta`

**Files:**
- Modify: `app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkData.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkDataMetaTest.kt`

**Interfaces:**
- Produces (consumed by Task 3):
  - `TimedRow` gains `val msPerFrameMin: Double, val msPerFrameMax: Double`.
  - `data class SnapshotMeta(val deviceModel: String?, val soc: String?, val ort: String?, val note: String?, val framesByModel: Map<String, Int>)`
  - `fun BenchmarkData.parseMeta(json: String): SnapshotMeta`

- [ ] **Step 1: Write the failing test** — `BenchmarkDataMetaTest.kt`

```kotlin
package com.example.clipcc.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkDataMetaTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test fun meta9a_hasProvenanceAndFrames() {
        val m = BenchmarkData.parseMeta(res("phase2-benchmark-result-9a.json"))
        assertEquals("Pixel 9a", m.deviceModel)
        assertEquals("Google Tensor G4", m.soc)
        assertEquals("1.26.0", m.ort)
        assertEquals(16, m.framesByModel["siglip2-base-patch16-256"])
        assertEquals(4, m.framesByModel["siglip2-so400m-patch14-384"])
    }

    @Test fun meta7a_noDeviceOrOrt_butHasFrames() {
        val m = BenchmarkData.parseMeta(res("phase2-benchmark-result.json"))
        assertNull(m.deviceModel)
        assertNull(m.ort)
        assertEquals(7, m.framesByModel["siglip2-base-patch16-256"])
        assertEquals(4, m.framesByModel["siglip2-so400m-patch14-384"])
    }

    @Test fun timedRow_perFrameSpread_bracketsMedian() {
        val g = BenchmarkData.parse(res("phase2-benchmark-result-9a.json"))
        val ep = g.first { it.modelId == "siglip2-so400m-patch14-384" }
            .timed.first { it.backend == "CPU_EP" }   // 9a so400m EP: noisy lane, 14450-17096 per frame
        assertTrue(ep.msPerFrameMin <= ep.msPerFrame)
        assertTrue(ep.msPerFrameMax >= ep.msPerFrame)
        assertTrue(ep.msPerFrameMin < ep.msPerFrameMax)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.clipcc.ui.benchmark.BenchmarkDataMetaTest"`
Expected: FAIL — `parseMeta` unresolved / `msPerFrameMin` unresolved.

- [ ] **Step 3: Add the fields + `parseMeta`** — edit `BenchmarkData.kt`

Change the `TimedRow` data class to add two fields:

```kotlin
data class TimedRow(
    val backend: String, val loadMs: Long, val visionMsMedian: Long,
    val msPerFrame: Double, val msPerFrameMin: Double, val msPerFrameMax: Double,
    val fps: Double, val visionDelegatedPct: Double?,
)
```

In `parse`, replace the `TimedRow(...)` construction inside the `runs` loop with the version that derives the per-frame spread from `visionMsMin/Max` (ratio against the median, so no separate frame count is needed):

```kotlin
val medD = r.getDouble("visionMsMedian")
val mpf = r.getDouble("msPerFrame")
fun perFrame(totalKey: String): Double {
    val v = r.getDouble(totalKey)
    return if (medD > 0) mpf * v / medD else mpf
}
timedByModel.getOrPut(model) { mutableListOf() }.add(
    TimedRow(
        backend = backend, loadMs = r.getLong("loadMs"),
        visionMsMedian = r.getLong("visionMsMedian"),
        msPerFrame = mpf,
        msPerFrameMin = perFrame("visionMsMin"),
        msPerFrameMax = perFrame("visionMsMax"),
        fps = r.getDouble("fps"),
        visionDelegatedPct = visionCoverage[model to backend],
    )
)
```

Append the metadata type + parser at the end of the file (inside the package, after the `object BenchmarkData` block — `parseMeta` is a top-level function):

```kotlin
data class SnapshotMeta(
    val deviceModel: String?, val soc: String?, val ort: String?, val note: String?,
    val framesByModel: Map<String, Int>,
)

/** Provenance + per-model sampled frame counts. Tolerant: absent fields → null / absent key.
 *  The 7a asset has no `device`/`ort`/`note` → those return null; `prep[].frames` drives protocol-match. */
fun BenchmarkData.parseMeta(json: String): SnapshotMeta {
    val root = org.json.JSONObject(json)
    val device = root.optJSONObject("device")
    val frames = LinkedHashMap<String, Int>()
    root.optJSONArray("prep")?.let { prep ->
        for (i in 0 until prep.length()) {
            val p = prep.getJSONObject(i)
            if (p.has("model") && p.has("frames")) frames[p.getString("model")] = p.getInt("frames")
        }
    }
    fun nullIfEmpty(s: String?) = s?.ifEmpty { null }
    return SnapshotMeta(
        deviceModel = nullIfEmpty(device?.optString("model")),
        soc = nullIfEmpty(device?.optString("soc")),
        ort = nullIfEmpty(root.optString("ort")),
        note = nullIfEmpty(root.optString("note")),
        framesByModel = frames,
    )
}
```

(If `org.json.JSONObject` is already imported at the top of the file, use the short name.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.clipcc.ui.benchmark.BenchmarkDataMetaTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full unit suite to confirm no regression** (TimedRow gained fields — make sure nothing constructs it positionally elsewhere)

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (existing 59 + new tests; if a `BenchmarkDataTest` constructs `TimedRow` directly it must be updated to the new arity — fix inline if so).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkData.kt app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkDataMetaTest.kt
git commit -m "feat: TimedRow per-frame min/max + SnapshotMeta/parseMeta"
```

---

### Task 3: `BenchmarkCompare` — join, fail-closed protocol-match, band-separation, load contract

**Files:**
- Create: `app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkCompare.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkCompareTest.kt`

**Interfaces:**
- Consumes: `ModelGroup`, `TimedRow` (with min/max), `SnapshotMeta`, `BenchmarkData.parse`, `BenchmarkData.parseMeta`.
- Produces (consumed by Task 4):
  - `LaneDelta`, `ModelDelta`, `Comparison`, `EmptyReason`, `CompareResult`.
  - `BenchmarkCompare.build(sevenA, nineA): Comparison`
  - `BenchmarkCompare.load(read7a: () -> String?, read9a: () -> String?): CompareResult`

- [ ] **Step 1: Write the failing test** — `BenchmarkCompareTest.kt`

```kotlin
package com.example.clipcc.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkCompareTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun snap(name: String) =
        BenchmarkData.parse(res(name)) to BenchmarkData.parseMeta(res(name))

    private val sevenA get() = snap("phase2-benchmark-result.json")
    private val nineA get() = snap("phase2-benchmark-result-9a.json")

    @Test fun hero_isXnnpack_about23pct_range8to37() {
        val c = BenchmarkCompare.build(sevenA, nineA)
        assertEquals(4, c.heroLaneCount)
        assertEquals(23.0, c.avgPctFaster, 1.5)
        assertEquals(8.0, c.minPctFaster, 1.5)
        assertEquals(37.0, c.maxPctFaster, 1.5)
        c.models.forEach { assertEquals("CPU_XNNPACK", it.headline!!.backend) }
        assertTrue(c.header.contains("Tensor G4"))
        assertTrue(c.header.contains("Tensor G2"))
    }

    @Test fun base256_xnnpack_pctFaster_matchesHandComputed_andBandSeparated() {
        val base = BenchmarkCompare.build(sevenA, nineA).models
            .first { it.modelId == "siglip2-base-patch16-256" }
        val xn = base.lanes.first { it.backend == "CPU_XNNPACK" }
        assertEquals(37.1, xn.pctFaster, 0.5)   // (2372.286-1491.313)/2372.286*100
        assertTrue(xn.protocolMatched)
        assertTrue(xn.bandSeparated)
        assertEquals(7, base.frames7a); assertEquals(16, base.frames9a)
    }

    @Test fun base_cpuEp_notProtocolMatched_excludedFromHeadline() {
        val base = BenchmarkCompare.build(sevenA, nineA).models
            .first { it.modelId == "siglip2-base-patch16-256" }
        assertFalse(base.lanes.first { it.backend == "CPU_EP" }.protocolMatched)  // 7 vs 16
        assertEquals("CPU_XNNPACK", base.headline!!.backend)
    }

    @Test fun so400m_cpuEp_protocolMatched_butXnnpackIsHeadline() {
        val so = BenchmarkCompare.build(sevenA, nineA).models
            .first { it.modelId == "siglip2-so400m-patch14-384" }
        assertTrue(so.lanes.first { it.backend == "CPU_EP" }.protocolMatched)     // 4 == 4
        assertEquals("CPU_XNNPACK", so.headline!!.backend)
    }

    @Test fun failClosed_missingFrameMeta_doesNotMatchCpuEp_butXnnpackStillMatches() {
        val (g7, _) = sevenA; val (g9, _) = nineA
        val emptyMeta = SnapshotMeta(null, null, null, null, emptyMap())
        val base = BenchmarkCompare.build(g7 to emptyMeta, g9 to emptyMeta).models
            .first { it.modelId == "siglip2-base-patch16-256" }
        assertFalse("null==null must not match", base.lanes.first { it.backend == "CPU_EP" }.protocolMatched)
        assertTrue(base.lanes.first { it.backend == "CPU_XNNPACK" }.protocolMatched)
    }

    @Test fun load_ok() {
        val r = BenchmarkCompare.load(
            read7a = { res("phase2-benchmark-result.json") },
            read9a = { res("phase2-benchmark-result-9a.json") })
        assertTrue(r is CompareResult.Ok)
        assertEquals(4, (r as CompareResult.Ok).comparison.heroLaneCount)
    }

    @Test fun load_missing9a_returnsNo9a() {
        val r = BenchmarkCompare.load(read7a = { res("phase2-benchmark-result.json") }, read9a = { null })
        assertEquals(CompareResult.Empty(EmptyReason.NO_9A), r)
    }

    @Test fun load_malformed7a_returnsNo7a() {
        val r = BenchmarkCompare.load(read7a = { "{ not json" },
            read9a = { res("phase2-benchmark-result-9a.json") })
        assertEquals(CompareResult.Empty(EmptyReason.NO_7A), r)
    }

    @Test fun load_noOverlap_returnsNoOverlap() {
        val lonely = """{"prep":[{"model":"other","res":256,"frames":7}],
            "runs":[{"model":"other","backend":"CPU_XNNPACK","loadMs":1,"visionMsMedian":10,
            "visionMsMin":10,"visionMsMax":10,"msPerFrame":10.0,"fps":1.0}],"capabilities":[]}"""
        val r = BenchmarkCompare.load(read7a = { lonely },
            read9a = { res("phase2-benchmark-result-9a.json") })
        assertEquals(CompareResult.Empty(EmptyReason.NO_OVERLAP), r)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.clipcc.ui.benchmark.BenchmarkCompareTest"`
Expected: FAIL — `BenchmarkCompare` unresolved.

- [ ] **Step 3: Implement** — create `BenchmarkCompare.kt`

```kotlin
package com.example.clipcc.ui.benchmark

/** One backend lane (e.g. CPU_XNNPACK) compared across the two devices. */
data class LaneDelta(
    val backend: String,
    val ms7a: Double, val ms7aMin: Double, val ms7aMax: Double,
    val ms9a: Double, val ms9aMin: Double, val ms9aMax: Double,
    val pctFaster: Double, val speedup: Double,
    val protocolMatched: Boolean, val bandSeparated: Boolean,
)
data class ModelDelta(
    val modelId: String, val frames7a: Int?, val frames9a: Int?,
    val lanes: List<LaneDelta>, val headline: LaneDelta?,
)
data class Comparison(
    val header: String, val models: List<ModelDelta>,
    val avgPctFaster: Double, val minPctFaster: Double, val maxPctFaster: Double, val heroLaneCount: Int,
)
enum class EmptyReason { NO_9A, NO_7A, NO_OVERLAP }

sealed interface CompareResult {
    data class Ok(val comparison: Comparison) : CompareResult
    data class Empty(val reason: EmptyReason) : CompareResult
}

object BenchmarkCompare {

    /** Pure join of two already-parsed snapshots. */
    fun build(
        sevenA: Pair<List<ModelGroup>, SnapshotMeta>,
        nineA: Pair<List<ModelGroup>, SnapshotMeta>,
    ): Comparison {
        val (g7, m7) = sevenA
        val (g9, m9) = nineA
        val by9 = g9.associateBy { it.modelId }
        val models = ArrayList<ModelDelta>()
        for (mg7 in g7) {
            val mg9 = by9[mg7.modelId] ?: continue
            val f7 = m7.framesByModel[mg7.modelId]
            val f9 = m9.framesByModel[mg7.modelId]
            val lanes9 = mg9.timed.associateBy { it.backend }
            val lanes = ArrayList<LaneDelta>()
            for (t7 in mg7.timed) {
                val t9 = lanes9[t7.backend] ?: continue
                val matched = t7.backend == "CPU_XNNPACK" || (f7 != null && f9 != null && f7 == f9)
                val pct = if (t7.msPerFrame > 0) (t7.msPerFrame - t9.msPerFrame) / t7.msPerFrame * 100 else 0.0
                val speed = if (t9.msPerFrame > 0) t7.msPerFrame / t9.msPerFrame else 0.0
                lanes.add(LaneDelta(
                    backend = t7.backend,
                    ms7a = t7.msPerFrame, ms7aMin = t7.msPerFrameMin, ms7aMax = t7.msPerFrameMax,
                    ms9a = t9.msPerFrame, ms9aMin = t9.msPerFrameMin, ms9aMax = t9.msPerFrameMax,
                    pctFaster = pct, speedup = speed,
                    protocolMatched = matched,
                    bandSeparated = t9.msPerFrameMax < t7.msPerFrameMin,
                ))
            }
            if (lanes.isEmpty()) continue
            models.add(ModelDelta(
                modelId = mg7.modelId, frames7a = f7, frames9a = f9, lanes = lanes,
                headline = lanes.filter { it.protocolMatched }.minByOrNull { it.ms9a },
            ))
        }
        val heads = models.mapNotNull { it.headline?.pctFaster }
        val soc9 = m9.soc ?: m9.deviceModel ?: "Tensor G4"
        val soc7 = m7.soc ?: "Tensor G2"
        val ort = m9.ort ?: m7.ort ?: "1.26.0"
        val header = "Pixel 9a · $soc9  vs  Pixel 7a · $soc7 · CPU-only · ms/frame (vision-encode) · ORT $ort"
        return Comparison(
            header = header, models = models,
            avgPctFaster = if (heads.isEmpty()) 0.0 else heads.average(),
            minPctFaster = heads.minOrNull() ?: 0.0,
            maxPctFaster = heads.maxOrNull() ?: 0.0,
            heroLaneCount = heads.size,
        )
    }

    /** Read + parse both snapshots, classifying failures. [read7a]/[read9a] return raw JSON or null
     *  (asset missing); they may throw on malformed JSON — caught here. */
    fun load(read7a: () -> String?, read9a: () -> String?): CompareResult {
        val s7 = parseSnapshot(read7a) ?: return CompareResult.Empty(EmptyReason.NO_7A)
        val s9 = parseSnapshot(read9a) ?: return CompareResult.Empty(EmptyReason.NO_9A)
        val cmp = build(s7, s9)
        return if (cmp.models.isEmpty()) CompareResult.Empty(EmptyReason.NO_OVERLAP)
        else CompareResult.Ok(cmp)
    }

    private fun parseSnapshot(read: () -> String?): Pair<List<ModelGroup>, SnapshotMeta>? =
        runCatching {
            val json = read() ?: return@runCatching null
            BenchmarkData.parse(json) to BenchmarkData.parseMeta(json)
        }.getOrNull()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.clipcc.ui.benchmark.BenchmarkCompareTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/benchmark/BenchmarkCompare.kt app/src/test/java/com/example/clipcc/ui/benchmark/BenchmarkCompareTest.kt
git commit -m "feat: BenchmarkCompare — fail-closed protocol-match + band-separation + load contract"
```

---

### Task 4: `Pixel9aScreen` — hero, per-model rows, reason-keyed empty state

**Files:**
- Create: `app/src/main/java/com/example/clipcc/ui/benchmark/Pixel9aScreen.kt`

**Interfaces:**
- Consumes: `BenchmarkCompare.load`, `CompareResult`, `Comparison`, `ModelDelta`, `LaneDelta`, `EmptyReason`.
- Produces (consumed by Task 5): `@Composable fun Pixel9aScreen()`.

- [ ] **Step 1: Create the screen** — `Pixel9aScreen.kt`

```kotlin
package com.example.clipcc.ui.benchmark

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun Pixel9aScreen() {
    val ctx = LocalContext.current
    val result = remember {
        fun read(name: String): () -> String? = {
            runCatching { ctx.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()
        }
        BenchmarkCompare.load(
            read7a = read("phase2-benchmark-result.json"),
            read9a = read("phase2-benchmark-result-9a.json"),
        )
    }
    when (result) {
        is CompareResult.Empty -> EmptyState(result.reason)
        is CompareResult.Ok -> Comparison9a(result.comparison)
    }
}

@Composable
private fun EmptyState(reason: EmptyReason) {
    val msg = when (reason) {
        EmptyReason.NO_9A -> "Pixel 9a snapshot not bundled."
        EmptyReason.NO_7A -> "Benchmark (Pixel 7a) snapshot unavailable."
        EmptyReason.NO_OVERLAP -> "No models common to both snapshots."
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Comparison9a(c: Comparison) {
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(c.header, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        ElevatedCard {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⚡ +${"%.0f".format(Locale.US, c.avgPctFaster)}% faster",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text("protocol-matched (XNNPACK) · avg over ${c.heroLaneCount} models · per-model " +
                    "+${"%.0f".format(Locale.US, c.minPctFaster)}–${"%.0f".format(Locale.US, c.maxPctFaster)}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        c.models.forEach { ModelCard(it) }
        Text("Spike A was not a sustained-load test; band-separation bounds 3-run repeatability, not thermal drift.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ModelCard(m: ModelDelta) {
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.modelId, style = MaterialTheme.typography.titleMedium)
            Text("frames: 7a ${m.frames7a ?: "?"} → 9a ${m.frames9a ?: "?"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            val matched = m.lanes.filter { it.protocolMatched }
            val unmatched = m.lanes.filter { !it.protocolMatched }
            matched.forEach { LaneRow(it, bold = it == m.headline) }
            if (unmatched.isNotEmpty()) {
                Text("— not protocol-matched (frame counts differ) —",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                unmatched.forEach { LaneRow(it, bold = false) }
            }
        }
    }
}

@Composable
private fun LaneRow(l: LaneDelta, bold: Boolean) {
    fun f(x: Double) = "%.0f".format(Locale.US, x)
    fun signed(x: Double) = (if (x >= 0) "+" else "") + f(x)
    val tail = if (l.bandSeparated)
        "${signed(l.pctFaster)}% (${"%.2f".format(Locale.US, l.speedup)}×)"
    else
        "≈ ${signed(l.pctFaster)}% (within 3-run spread)"
    Text(
        "${l.backend}: ${f(l.ms7a)} [${f(l.ms7aMin)}–${f(l.ms7aMax)}] → " +
            "${f(l.ms9a)} [${f(l.ms9aMin)}–${f(l.ms9aMax)}] ms/frame   $tail",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
}
```

- [ ] **Step 2: Verify it compiles** (no JVM unit test — behavior is gated by the route test in Task 6)

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/benchmark/Pixel9aScreen.kt
git commit -m "feat: Pixel9aScreen — hero, per-model rows, reason-keyed empty state"
```

---

### Task 5: `AppTab` enum + third route in `ClipCCApp` + JVM test

**Files:**
- Create: `app/src/main/java/com/example/clipcc/ui/app/AppTab.kt`
- Modify: `app/src/main/java/com/example/clipcc/ui/app/ClipCCApp.kt`
- Test: `app/src/test/java/com/example/clipcc/ui/app/AppTabTest.kt`

**Interfaces:**
- Consumes: `Pixel9aScreen` (Task 4), `BenchmarkScreen`, `ClassifyTab` (existing).
- Produces: `enum class AppTab(val title: String)` {CLASSIFY, BENCHMARK, PIXEL9A}; an exhaustive `when` route in `ClipCCApp`.

- [ ] **Step 1: Write the failing test** — `AppTabTest.kt`

```kotlin
package com.example.clipcc.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTabTest {
    @Test fun titlesInDisplayOrder() {
        assertEquals(listOf("Classify", "Benchmark", "Pixel 9a"), AppTab.entries.map { it.title })
    }

    @Test fun pixel9aIsThirdTab() {
        assertEquals(2, AppTab.PIXEL9A.ordinal)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.clipcc.ui.app.AppTabTest"`
Expected: FAIL — `AppTab` unresolved.

- [ ] **Step 3: Create the enum** — `AppTab.kt`

```kotlin
package com.example.clipcc.ui.app

/** App tabs in display order. Drives both the TabRow labels and the content router so the two
 *  cannot drift apart. */
enum class AppTab(val title: String) {
    CLASSIFY("Classify"),
    BENCHMARK("Benchmark"),
    PIXEL9A("Pixel 9a"),
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.clipcc.ui.app.AppTabTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire `ClipCCApp` to the enum** — edit `ClipCCApp.kt`

Add the import near the other `ui.benchmark` import:

```kotlin
import com.example.clipcc.ui.benchmark.Pixel9aScreen
```

Replace the body of the `Scaffold`'s `Column` (the `TabRow` + `if (tab == 0) ... else ...`) with:

```kotlin
Column(Modifier.padding(pad)) {
    TabRow(selectedTabIndex = tab) {
        AppTab.entries.forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.title) })
        }
    }
    when (AppTab.entries[tab]) {
        AppTab.CLASSIFY -> ClassifyTab(onKeepAwake)
        AppTab.BENCHMARK -> BenchmarkScreen()
        AppTab.PIXEL9A -> Pixel9aScreen()
    }
}
```

Also delete the now-unused `val titles = listOf("Classify", "Benchmark")` line.

- [ ] **Step 6: Verify the app compiles + suite still green**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/clipcc/ui/app/AppTab.kt app/src/main/java/com/example/clipcc/ui/app/ClipCCApp.kt app/src/test/java/com/example/clipcc/ui/app/AppTabTest.kt
git commit -m "feat: AppTab enum + exhaustive third-tab route (Pixel 9a)"
```

---

### Task 6: Instrumented route test (`ClipCCAppRouteTest`)

**Files:**
- Modify: `app/build.gradle.kts` (wire `debugImplementation` ui-test-manifest)
- Test: `app/src/androidTest/java/com/example/clipcc/ui/app/ClipCCAppRouteTest.kt`

**Interfaces:**
- Consumes: `ClipCCApp(onKeepAwake)`, Compose UI test (`androidx.compose.ui.test.junit4`, already an `androidTestImplementation`).
- Produces: route coverage that tab index 2 renders Pixel9a content and NOT the 7a Benchmark screen.

> **Device/emulator required.** This is the only instrumented test in this plan; needs an emulator or the Pixel (no models/clip needed — assets are in the APK).
>
> **Gotcha:** use `createAndroidComposeRule<ComponentActivity>()` (not the blank `createComposeRule()`) so `viewModel()` inside `ClassifyTab` has a `ViewModelStoreOwner`. `ui-test-manifest` provides the `ComponentActivity` the rule launches.

- [ ] **Step 1: Wire the test-manifest dependency** — edit `app/build.gradle.kts`, in the `dependencies { }` block near the other compose-test lines:

```kotlin
    debugImplementation(libs.androidx.compose.ui.test.manifest)
```

(The catalog alias `androidx-compose-ui-test-manifest` already exists in `gradle/libs.versions.toml`.)

- [ ] **Step 2: Write the route test** — `ClipCCAppRouteTest.kt`

```kotlin
package com.example.clipcc.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import org.junit.Rule
import org.junit.Test

@UnstableApi
class ClipCCAppRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pixel9aTab_rendersComparison_notThe7aBenchmarkScreen() {
        compose.setContent { ClipCCApp(onKeepAwake = {}) }

        compose.onNodeWithText("Pixel 9a").performClick()

        // Pixel9a-specific content (the hero "% faster" line) is shown…
        compose.onNodeWithText("% faster", substring = true).assertIsDisplayed()
        // …and the 7a-only Benchmark header ("median-of-3") is NOT rendered.
        compose.onNodeWithText("median-of-3", substring = true).assertDoesNotExist()
    }
}
```

- [ ] **Step 3: Run on a connected device/emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.example.clipcc.ui.app.ClipCCAppRouteTest"`
Expected: PASS — tab 2 shows the hero, 7a header absent.

(If `viewModel()` throws "No ViewModelStoreOwner": confirm the rule is `createAndroidComposeRule<ComponentActivity>()` and `ui-test-manifest` is wired. If the "Pixel 9a" tab label needs scrolling into view on a small screen, it won't — three tabs fit `TabRow`.)

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/androidTest/java/com/example/clipcc/ui/app/ClipCCAppRouteTest.kt
git commit -m "test: instrumented route assertion — tab 2 renders Pixel 9a content"
```

---

## Final verification

- [ ] **Full unit suite** — `./gradlew :app:testDebugUnitTest` → all green (existing 59 + Benchmark9aAsset 1 + BenchmarkDataMeta 3 + BenchmarkCompare 10 + AppTab 2).
- [ ] **Instrumented route test** — `./gradlew :app:connectedDebugAndroidTest --tests "com.example.clipcc.ui.app.ClipCCAppRouteTest"` → green on device/emulator.
- [ ] **Manual** — install, open the app, tap **Pixel 9a**: hero reads `⚡ +23% faster`, four model cards show `7a … → 9a …` rows with per-lane spreads, base/large show a "not protocol-matched" CPU_EP sub-row. Screenshot for the report.
- [ ] **Existing parity untouched** — `BenchmarkScreen` (Benchmark tab) and engine tests unchanged.

## Notes for the executor

- **Do not** introduce a noise-threshold constant; robustness is `bandSeparated = ms9aMax < ms7aMin` only.
- **Do not** modify `BenchmarkData.parse`'s existing fields or `BenchmarkScreen`.
- If `BenchmarkDataTest` (existing) constructs `TimedRow` positionally, update it for the two new fields (Task 2, Step 5).
- The 9a numbers come only from the committed Spike-A JSON; no device benchmark run is part of this plan.
