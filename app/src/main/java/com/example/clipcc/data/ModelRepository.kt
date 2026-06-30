package com.example.clipcc.data

import com.example.clipcc.engine.ModelBundleManifest
import com.example.clipcc.engine.Precision
import com.example.clipcc.engine.ScoringPolicy
import java.io.File

data class ModelInfo(
    val id: String, val displayName: String, val resolution: Int, val precision: String,
    val scoreSemantics: String, val ready: Boolean, val reason: String?, val dir: String,
    /** Precisions declared in the manifest whose vision+text files are actually present on disk. */
    val availablePrecisions: List<Precision> = emptyList(),
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
        // Offer a precision only if BOTH its towers match the manifest's declared size. A truncated /
        // wrong-variant / corrupt file (e.g. a bad int8 push) is dropped from the picker instead of
        // loading and silently emitting zero-confidence embeddings. length() is 0 for a missing file,
        // so this also covers existence.
        // ponytail: size-only (cheap, runs every scan); sha256 would hash ~GBs per launch — make it an
        // on-demand "deep verify" if bit-rot of a right-sized file ever matters.
        val avail = m.availablePrecisions.filter { p ->
            val f = m.filesFor(p)
            File(dir, f.visionFile).length() == f.visionBytes &&
                File(dir, f.textFile).length() == f.textBytes
        }
        return ModelInfo(
            id = dir.name, displayName = m.displayName, resolution = m.resolution,
            precision = m.precision, scoreSemantics = m.scoreSemantics,
            ready = reason == null, reason = reason, dir = dir.absolutePath,
            availablePrecisions = avail,
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
