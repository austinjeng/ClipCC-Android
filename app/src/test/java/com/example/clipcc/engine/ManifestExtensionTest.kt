package com.example.clipcc.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestExtensionTest {
    private val json = ManifestExtensionTest::class.java.classLoader!!
        .getResource("manifest_base256.json")!!.readText()

    @Test fun parses_new_v1_fields() {
        val m = ModelBundleManifest.parse(json)
        assertEquals("SigLIP2 Base (256px)", m.displayName)
        assertEquals("siglip2_pairwise_sigmoid", m.scoreSemantics)
        assertEquals(371992072L, m.visionBytes)
        assertEquals(1129469657L, m.textBytes)
        assertEquals("f5cb16728a704703f05516ded628397e11dbca4de2eb5db04b0c0bcee988aa7a", m.visionSha256)
        assertEquals("d3de4a6bbbfcb429b6615ac496790353cf4a4fc0f19fbbe7179e523ae60daaef", m.textSha256)
        assertEquals("cb9140fae3ac5122c972d37adf83e1248471a38147ad76f8215c8872c6fd8322", m.tokenizerSha256)
    }
}
