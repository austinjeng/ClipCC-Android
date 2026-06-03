package com.example.clipcc.engine

import ai.onnxruntime.OrtEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Per-(model,tower,backend) capability evidence from an UNTIMED, profiling-ON one-frame run. */
data class BackendCapabilityReport(
    val modelId: String,
    val tower: String,          // "vision" | "text"
    val backend: Backend,
    val addEpOutcome: String,
    val sessionCreateOutcome: String,
    val providerCounts: Map<String, Int>,  // provider -> node count (from profiling JSON)
    val totalNodes: Int,
    val delegatedPctByProvider: Map<String, Double>,
) {
    fun toJson(): String {
        val pc = providerCounts.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        val pct = delegatedPctByProvider.entries.joinToString(",") { "\"${it.key}\":${String.format(java.util.Locale.US, "%.2f", it.value)}" }
        return """{"model":"$modelId","tower":"$tower","backend":"$backend","addEp":${JSONObject.quote(addEpOutcome)},""" +
               """"sessionCreate":${JSONObject.quote(sessionCreateOutcome)},"totalNodes":$totalNodes,""" +
               """"providerCounts":{$pc},"delegatedPct":{$pct}}"""
    }
}

object BackendCapability {
    /** Parse ORT profiling JSON: count Node events by their args.provider. (Spike 0b format.) */
    fun parseProviderCounts(profileJsonPath: String): Map<String, Int> {
        val text = File(profileJsonPath).readText()
        val arr = JSONArray(text)
        val counts = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val ev = arr.optJSONObject(i) ?: continue
            if (ev.optString("cat") != "Node") continue
            val args = ev.optJSONObject("args") ?: continue
            val provider = args.optString("provider", "")
            if (provider.isEmpty()) continue
            counts[provider] = (counts[provider] ?: 0) + 1
        }
        return counts
    }

    /** Probe vision tower of [modelDir] under [backend]; one untimed frame; profiling -> coverage. */
    fun probeVision(
        modelDir: String, modelId: String, res: Int, backend: Backend,
        env: OrtEnvironment, cacheDir: File, dummy: FloatBuffer,
    ): BackendCapabilityReport {
        var outcome = OpenOutcome(backend, "n/a", "not-attempted")
        val profileBase = File(cacheDir, "prof_${modelId}_vision_$backend").absolutePath
        var counts: Map<String, Int> = emptyMap()
        try {
            OrtTower.open("$modelDir/vision_model.onnx", env, backend,
                profilePath = profileBase, outcome = { outcome = it }).use { tower ->
                val path = tower.runOnceForProfile(dummy, res)
                counts = parseProviderCounts(path)
            }
        } catch (t: Throwable) {
            // outcome already records the throw; counts stays empty
        }
        val total = counts.values.sum()
        val pct = if (total > 0) counts.mapValues { 100.0 * it.value / total } else emptyMap()
        return BackendCapabilityReport(modelId, "vision", backend, outcome.addEpOutcome,
            outcome.sessionCreateOutcome, counts, total, pct)
    }

    /** Probe text tower of [modelDir] under [backend]; one untimed label; profiling -> coverage. */
    fun probeText(
        modelDir: String, modelId: String, maxLen: Int, backend: Backend,
        env: OrtEnvironment, cacheDir: File,
    ): BackendCapabilityReport {
        var outcome = OpenOutcome(backend, "n/a", "not-attempted")
        val profileBase = File(cacheDir, "prof_${modelId}_text_$backend").absolutePath
        var counts: Map<String, Int> = emptyMap()
        try {
            OrtTower.open("$modelDir/text_model.onnx", env, backend,
                profilePath = profileBase, outcome = { outcome = it }).use { tower ->
                counts = parseProviderCounts(tower.runOnceForProfileText(LongArray(maxLen) { 0L }, maxLen))
            }
        } catch (t: Throwable) { /* outcome records the throw */ }
        val total = counts.values.sum()
        val pct = if (total > 0) counts.mapValues { 100.0 * it.value / total } else emptyMap()
        return BackendCapabilityReport(modelId, "text", backend, outcome.addEpOutcome,
            outcome.sessionCreateOutcome, counts, total, pct)
    }

    fun dummyFrame(res: Int): FloatBuffer {
        val per = 3 * res * res
        val buf = ByteBuffer.allocateDirect(per * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until per) buf.put(0f)
        (buf as Buffer).rewind(); return buf
    }
}
