package com.example.clipcc.data

import com.example.clipcc.engine.ModelBundleManifest
import com.example.clipcc.engine.ScoringPolicy
import java.io.File

data class ModelInfo(
    val id: String, val displayName: String, val resolution: Int, val precision: String,
    val scoreSemantics: String, val ready: Boolean, val reason: String?, val dir: String,
)

/** Scans [modelsRoot]/<id>/manifest.json bundles. Readiness = parse + files-exist + size-match
 *  (vision/text only — v1 has no tokenizer `bytes`) + supported semantics. */
class ModelRepository(private val modelsRoot: File) {
    fun scan(): List<ModelInfo> {
        val dirs = modelsRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return dirs.mapNotNull { dir -> infoFor(dir) }
    }

    private fun infoFor(dir: File): ModelInfo? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return null
        val m = try {
            ModelBundleManifest.parse(manifestFile.readText())
        } catch (t: Throwable) {
            return ModelInfo(dir.name, dir.name, 0, "", "", false, "bad manifest: ${t.message}", dir.absolutePath)
        }
        val reason = readinessReason(dir, m)
        return ModelInfo(
            id = dir.name, displayName = m.displayName, resolution = m.resolution,
            precision = m.precision, scoreSemantics = m.scoreSemantics,
            ready = reason == null, reason = reason, dir = dir.absolutePath,
        )
    }

    private fun readinessReason(dir: File, m: ModelBundleManifest): String? {
        val required = buildList {
            add(m.visionFile); add(m.textFile); add(m.tokenizerFile)
            m.visionDataFile?.let { add(it) }; m.textDataFile?.let { add(it) }
        }
        if (required.any { !File(dir, it).exists() }) return "not provisioned"
        if (File(dir, m.visionFile).length() != m.visionBytes) return "size mismatch"
        if (File(dir, m.textFile).length() != m.textBytes) return "size mismatch"
        if (m.scoreSemantics != ScoringPolicy.SCORE_SEMANTICS) return "unsupported semantics"
        return null
    }
}
