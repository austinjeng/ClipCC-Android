package com.example.clipcc.engine

import org.json.JSONObject

data class ModelBundleManifest(
    val schemaVersion: Int, val modelId: String, val displayName: String, val resolution: Int,
    val precision: String, val scoreSemantics: String,
    val logitScale: Double, val logitBias: Double,
    val visionFile: String, val visionDataFile: String?, val visionBytes: Long, val visionSha256: String,
    val textFile: String, val textDataFile: String?, val textBytes: Long, val textSha256: String,
    val tokenizerFile: String, val tokenizerSha256: String, val lowercaseAppliedBy: String,
    val resample: String, val maxLength: Int, val padId: Int,
) {
    companion object {
        fun parse(json: String): ModelBundleManifest {
            val d = JSONObject(json)
            require(d.getInt("schema_version") == 1) { "unsupported schema_version" }
            val v = d.getJSONObject("vision"); val t = d.getJSONObject("text")
            val tok = d.getJSONObject("tokenizer"); val pp = d.getJSONObject("preprocess")
            return ModelBundleManifest(
                schemaVersion = 1, modelId = d.getString("model_id"),
                displayName = d.getString("display_name"),
                resolution = d.getInt("resolution"), precision = d.getString("precision"),
                scoreSemantics = d.getString("score_semantics"),
                logitScale = d.getDouble("logit_scale"), logitBias = d.getDouble("logit_bias"),
                visionFile = v.getString("file"),
                visionDataFile = if (v.isNull("data_file")) null else v.getString("data_file"),
                visionBytes = v.getLong("bytes"), visionSha256 = v.getString("sha256"),
                textFile = t.getString("file"),
                textDataFile = if (t.isNull("data_file")) null else t.getString("data_file"),
                textBytes = t.getLong("bytes"), textSha256 = t.getString("sha256"),
                tokenizerFile = tok.getString("file"), tokenizerSha256 = tok.getString("sha256"),
                lowercaseAppliedBy = tok.getString("lowercase_applied_by"),
                resample = pp.getString("resample"), maxLength = tok.getInt("max_length"),
                padId = tok.getInt("pad_id"),
            )
        }
    }
}
