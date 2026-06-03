package com.example.clipcc.data

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
}
