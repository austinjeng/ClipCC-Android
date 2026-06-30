package com.example.clipcc.ui.benchmark

import org.json.JSONObject

data class TimedRow(
    val backend: String, val loadMs: Long, val visionMsMedian: Long,
    val msPerFrame: Double, val msPerFrameMin: Double, val msPerFrameMax: Double,
    val fps: Double, val visionDelegatedPct: Double?,
)
data class CapabilityRow(val backend: String, val visionDelegatedPct: Double, val experimental: Boolean)
data class ModelGroup(val modelId: String, val timed: List<TimedRow>, val capabilityOnly: List<CapabilityRow>)

/** Parses the captured phase-2 snapshot. CPU lanes (with timing) -> [timed]; NNAPI lanes (no
 *  timing, capability probe only) -> [capabilityOnly]. Vision node-coverage % joined from `capabilities`. */
object BenchmarkData {
    private const val XNNPACK = "XnnpackExecutionProvider"

    fun parse(json: String): List<ModelGroup> {
        val root = JSONObject(json)
        val runs = root.getJSONArray("runs")
        val caps = root.getJSONArray("capabilities")

        val visionCoverage = HashMap<Pair<String, String>, Double>()
        for (i in 0 until caps.length()) {
            val c = caps.getJSONObject(i)
            if (c.getString("tower") != "vision") continue
            val pct = c.getJSONObject("delegatedPct")
            visionCoverage[c.getString("model") to c.getString("backend")] = pct.optDouble(XNNPACK, 0.0)
        }

        val timedByModel = LinkedHashMap<String, MutableList<TimedRow>>()
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            val model = r.getString("model"); val backend = r.getString("backend")
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
        }

        val timedBackends = HashMap<String, MutableSet<String>>()
        timedByModel.forEach { (m, rows) -> timedBackends[m] = rows.map { it.backend }.toMutableSet() }
        val capByModel = LinkedHashMap<String, MutableList<CapabilityRow>>()
        for (i in 0 until caps.length()) {
            val c = caps.getJSONObject(i)
            if (c.getString("tower") != "vision") continue
            val model = c.getString("model"); val backend = c.getString("backend")
            if (timedBackends[model]?.contains(backend) == true) continue
            val pct = c.getJSONObject("delegatedPct").optDouble(XNNPACK, 0.0)
            capByModel.getOrPut(model) { mutableListOf() }
                .add(CapabilityRow(backend, pct, experimental = backend.startsWith("NNAPI")))
        }

        return timedByModel.keys.map { m ->
            ModelGroup(m, timedByModel[m] ?: emptyList(), capByModel[m] ?: emptyList())
        }
    }
}

data class SnapshotMeta(
    val deviceModel: String?, val soc: String?, val ort: String?, val note: String?,
    val framesByModel: Map<String, Int>,
)

/** Provenance + per-model sampled frame counts. Tolerant: absent fields → null / absent key.
 *  The 7a asset has no `device`/`ort`/`note` → those return null; `prep[].frames` drives protocol-match. */
fun BenchmarkData.parseMeta(json: String): SnapshotMeta {
    val root = JSONObject(json)
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
