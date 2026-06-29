package com.example.clipcc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestV2Test {
    private fun load(name: String) =
        ModelBundleManifest.parse(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test fun parses_v2_precisions_map() {
        val m = load("manifest_base256_v2.json")
        assertEquals(2, m.schemaVersion)
        assertEquals(listOf(Precision.FP32, Precision.FP16, Precision.INT8), m.availablePrecisions)
        assertEquals(Precision.FP16, m.defaultPrecision)
        assertEquals("vision_model_int8.onnx", m.filesFor(Precision.INT8).visionFile)
        assertEquals("text_model_fp16.onnx", m.filesFor(Precision.FP16).textFile)
        assertEquals("vision_model.onnx", m.filesFor(Precision.FP32).visionFile)
    }

    @Test fun v2_flat_fields_mirror_default_precision() {
        val m = load("manifest_base256_v2.json")
        // default = fp16 → flat fields point at the fp16 files (back-compat with v1 callers)
        assertEquals("vision_model_fp16.onnx", m.visionFile)
        assertEquals("text_model_fp16.onnx", m.textFile)
    }

    @Test fun v1_bundle_falls_back_to_single_precision() {
        val m = load("manifest_base256.json")               // the existing v1 fixture
        assertEquals(1, m.schemaVersion)
        assertEquals(listOf(Precision.FP32), m.availablePrecisions)
        assertEquals(Precision.FP32, m.defaultPrecision)
        assertEquals("vision_model.onnx", m.filesFor(Precision.FP32).visionFile)
        assertEquals("vision_model.onnx", m.visionFile)     // flat fields unchanged
    }

    @Test fun filesFor_missing_precision_throws() {
        val m = load("manifest_base256.json")
        assertTrue(runCatching { m.filesFor(Precision.INT8) }.isFailure)
    }
}
