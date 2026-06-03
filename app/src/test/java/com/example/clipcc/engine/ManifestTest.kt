package com.example.clipcc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestTest {
    @Test fun parses_schema_v1() {
        val json = javaClass.classLoader!!.getResource("manifest_base256.json")!!.readText()
        val m = ModelBundleManifest.parse(json)
        assertEquals(1, m.schemaVersion)
        assertEquals("siglip2-base-patch16-256", m.modelId)
        assertEquals(256, m.resolution)
        assertEquals("bilinear", m.resample)
        assertEquals("tokenizer_json", m.lowercaseAppliedBy)
        assertEquals("vision_model.onnx", m.visionFile)
        assertEquals("text_model.onnx", m.textFile)
        assertNull(m.visionDataFile)   // base-256 single-file; guards the org.json null-handling
        assertNull(m.textDataFile)
        // logit_scale/bias are read as Doubles
        assertEquals(4.7265, m.logitScale, 1e-3)
    }
}
