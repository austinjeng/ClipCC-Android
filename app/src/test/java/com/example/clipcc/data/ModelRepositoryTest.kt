package com.example.clipcc.data

import com.example.clipcc.engine.Precision
import com.example.clipcc.engine.ScoringPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun manifest(visBytes: Long, txtBytes: Long, semantics: String = ScoringPolicy.SCORE_SEMANTICS) = """
        {"schema_version":1,"model_id":"siglip2-base-patch16-256","display_name":"SigLIP2 Base (256px)",
         "resolution":256,"precision":"fp32","score_semantics":"$semantics",
         "logit_scale":4.7,"logit_bias":-16.7,
         "vision":{"file":"vision_model.onnx","data_file":null,"bytes":$visBytes,"sha256":"a"},
         "text":{"file":"text_model.onnx","data_file":null,"bytes":$txtBytes,"sha256":"b"},
         "tokenizer":{"file":"tokenizer.json","sha256":"c","max_length":64,"pad_id":0,
           "lowercase_applied_by":"tokenizer_json"},
         "preprocess":{"resample":"bilinear"}}
    """.trimIndent()

    private fun bundle(id: String, visBytes: Long, txtBytes: Long,
                       visActual: Int, txtActual: Int, semantics: String = ScoringPolicy.SCORE_SEMANTICS,
                       withTokenizer: Boolean = true): File {
        val dir = File(tmp.root, "models/$id").apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifest(visBytes, txtBytes, semantics))
        File(dir, "vision_model.onnx").writeBytes(ByteArray(visActual))
        File(dir, "text_model.onnx").writeBytes(ByteArray(txtActual))
        if (withTokenizer) File(dir, "tokenizer.json").writeText("{}")
        return dir
    }

    private fun repo() = ModelRepository(File(tmp.root, "models"))

    @Test fun ready_when_files_present_and_sizes_match() {
        bundle("m1", visBytes = 10, txtBytes = 20, visActual = 10, txtActual = 20)
        val info = repo().scan().single()
        assertEquals("m1", info.id)
        assertEquals("SigLIP2 Base (256px)", info.displayName)
        assertEquals(true, info.ready)
        assertNull(info.reason)
    }

    @Test fun not_ready_when_vision_size_mismatch() {
        bundle("m1", visBytes = 10, txtBytes = 20, visActual = 9, txtActual = 20)
        val info = repo().scan().single()
        assertEquals(false, info.ready)
        assertEquals("size mismatch", info.reason)
    }

    @Test fun not_ready_when_file_missing() {
        bundle("m1", visBytes = 10, txtBytes = 20, visActual = 10, txtActual = 20, withTokenizer = false)
        val info = repo().scan().single()
        assertEquals(false, info.ready)
        assertEquals("not provisioned", info.reason)
    }

    @Test fun not_ready_when_unsupported_semantics() {
        bundle("m1", visBytes = 10, txtBytes = 20, visActual = 10, txtActual = 20, semantics = "clip_softmax")
        val info = repo().scan().single()
        assertEquals(false, info.ready)
        assertEquals("unsupported semantics", info.reason)
    }

    @Test fun empty_root_returns_empty() {
        assertEquals(emptyList<ModelInfo>(), repo().scan())
    }

    // --- v2 multi-precision: a non-default precision tower that's the wrong size must not be offered.
    private fun manifestV2(fp16Vis: Long, fp16Txt: Long, int8Vis: Long, int8Txt: Long) = """
        {"schema_version":2,"model_id":"siglip2-base-patch16-256","display_name":"SigLIP2 Base (256px)",
         "resolution":256,"precision":"fp16","default_precision":"fp16",
         "score_semantics":"${ScoringPolicy.SCORE_SEMANTICS}","logit_scale":4.7,"logit_bias":-16.7,
         "precisions":{
           "fp16":{"vision":{"file":"vision_model_fp16.onnx","data_file":null,"bytes":$fp16Vis,"sha256":"a"},
                   "text":{"file":"text_model_fp16.onnx","data_file":null,"bytes":$fp16Txt,"sha256":"b"}},
           "int8":{"vision":{"file":"vision_model_int8.onnx","data_file":null,"bytes":$int8Vis,"sha256":"c"},
                   "text":{"file":"text_model_int8.onnx","data_file":null,"bytes":$int8Txt,"sha256":"d"}}},
         "tokenizer":{"file":"tokenizer.json","sha256":"e","max_length":64,"pad_id":0,
           "lowercase_applied_by":"tokenizer_json"},
         "preprocess":{"resample":"bilinear"}}
    """.trimIndent()

    private fun bundleV2(id: String, int8VisActual: Int, int8TxtActual: Int): File {
        val dir = File(tmp.root, "models/$id").apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifestV2(fp16Vis = 10, fp16Txt = 20, int8Vis = 5, int8Txt = 6))
        File(dir, "vision_model_fp16.onnx").writeBytes(ByteArray(10))
        File(dir, "text_model_fp16.onnx").writeBytes(ByteArray(20))
        File(dir, "vision_model_int8.onnx").writeBytes(ByteArray(int8VisActual))
        File(dir, "text_model_int8.onnx").writeBytes(ByteArray(int8TxtActual))
        File(dir, "tokenizer.json").writeText("{}")
        return dir
    }

    @Test fun availablePrecisions_excludes_wrong_size_tower() {
        bundleV2("m1", int8VisActual = 4, int8TxtActual = 6)   // int8 vision truncated (4 != 5)
        val info = repo().scan().single()
        assertEquals(true, info.ready)                          // default fp16 still fine → model usable
        assertEquals(listOf(Precision.FP16), info.availablePrecisions)   // int8 dropped from picker
    }

    @Test fun availablePrecisions_includes_correct_size_tower() {
        bundleV2("m1", int8VisActual = 5, int8TxtActual = 6)   // int8 sizes match manifest
        val info = repo().scan().single()
        assertEquals(listOf(Precision.FP16, Precision.INT8), info.availablePrecisions)
    }
}
