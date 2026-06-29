package com.example.clipcc.engine

import org.json.JSONObject

/** Vision+text tower files for one precision variant. */
data class PrecisionFiles(
    val visionFile: String, val visionDataFile: String?, val visionBytes: Long, val visionSha256: String,
    val textFile: String, val textDataFile: String?, val textBytes: Long, val textSha256: String,
)

data class ModelBundleManifest(
    val schemaVersion: Int, val modelId: String, val displayName: String, val resolution: Int,
    val precision: String, val scoreSemantics: String,
    val logitScale: Double, val logitBias: Double,
    // Flat fields below mirror the DEFAULT precision's files (back-compat with v1 callers).
    val visionFile: String, val visionDataFile: String?, val visionBytes: Long, val visionSha256: String,
    val textFile: String, val textDataFile: String?, val textBytes: Long, val textSha256: String,
    val tokenizerFile: String, val tokenizerSha256: String, val lowercaseAppliedBy: String,
    val resample: String, val maxLength: Int, val padId: Int,
    val defaultPrecision: Precision,
    val precisions: Map<Precision, PrecisionFiles>,
) {
    /** Available precisions, ordered FP32 < FP16 < INT8 (enum order). */
    val availablePrecisions: List<Precision> get() = precisions.keys.sortedBy { it.ordinal }

    fun filesFor(p: Precision): PrecisionFiles =
        precisions[p] ?: error("precision ${p.key} not available for $modelId")

    companion object {
        private fun filesFrom(v: JSONObject, t: JSONObject) = PrecisionFiles(
            visionFile = v.getString("file"),
            visionDataFile = if (v.isNull("data_file")) null else v.getString("data_file"),
            visionBytes = v.getLong("bytes"), visionSha256 = v.getString("sha256"),
            textFile = t.getString("file"),
            textDataFile = if (t.isNull("data_file")) null else t.getString("data_file"),
            textBytes = t.getLong("bytes"), textSha256 = t.getString("sha256"),
        )

        fun parse(json: String): ModelBundleManifest {
            val d = JSONObject(json)
            val schema = d.getInt("schema_version")
            require(schema == 1 || schema == 2) { "unsupported schema_version $schema" }
            val tok = d.getJSONObject("tokenizer"); val pp = d.getJSONObject("preprocess")

            val precisions = LinkedHashMap<Precision, PrecisionFiles>()
            val defaultPrecision: Precision
            if (d.has("precisions")) {                       // v2: multi-precision
                val pj = d.getJSONObject("precisions")
                for (k in pj.keys()) {
                    val pf = pj.getJSONObject(k)
                    precisions[Precision.fromKey(k)] =
                        filesFrom(pf.getJSONObject("vision"), pf.getJSONObject("text"))
                }
                defaultPrecision = Precision.fromKey(d.getString("default_precision"))
            } else {                                         // v1: single implicit precision
                val one = Precision.fromKey(d.getString("precision"))
                precisions[one] = filesFrom(d.getJSONObject("vision"), d.getJSONObject("text"))
                defaultPrecision = one
            }
            val def = precisions[defaultPrecision]
                ?: error("default_precision ${defaultPrecision.key} absent from precisions")

            return ModelBundleManifest(
                schemaVersion = schema, modelId = d.getString("model_id"),
                displayName = d.getString("display_name"),
                resolution = d.getInt("resolution"),
                precision = d.optString("precision", defaultPrecision.key),
                scoreSemantics = d.getString("score_semantics"),
                logitScale = d.getDouble("logit_scale"), logitBias = d.getDouble("logit_bias"),
                visionFile = def.visionFile, visionDataFile = def.visionDataFile,
                visionBytes = def.visionBytes, visionSha256 = def.visionSha256,
                textFile = def.textFile, textDataFile = def.textDataFile,
                textBytes = def.textBytes, textSha256 = def.textSha256,
                tokenizerFile = tok.getString("file"), tokenizerSha256 = tok.getString("sha256"),
                lowercaseAppliedBy = tok.getString("lowercase_applied_by"),
                resample = pp.getString("resample"), maxLength = tok.getInt("max_length"),
                padId = tok.getInt("pad_id"),
                defaultPrecision = defaultPrecision, precisions = precisions,
            )
        }
    }
}
