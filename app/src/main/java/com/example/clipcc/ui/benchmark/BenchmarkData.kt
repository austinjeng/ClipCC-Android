package com.example.clipcc.ui.benchmark

import org.json.JSONObject

data class TimedRow(
    val backend: String, val loadMs: Long, val visionMsMedian: Long,
    val msPerFrame: Double, val fps: Double, val visionDelegatedPct: Double?,
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
            timedByModel.getOrPut(model) { mutableListOf() }.add(
                TimedRow(
                    backend = backend, loadMs = r.getLong("loadMs"),
                    visionMsMedian = r.getLong("visionMsMedian"),
                    msPerFrame = r.getDouble("msPerFrame"), fps = r.getDouble("fps"),
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
