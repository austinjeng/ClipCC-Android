package com.example.clipcc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecisionPolicyTest {
    private val base = "siglip2-base-patch16-256"
    private val so400m = "siglip2-so400m-patch14-384"

    @Test fun recommended_argmax_is_int8_threshold_is_fp16() {
        assertEquals(Precision.INT8, PrecisionPolicy.recommended(thresholdMode = false))
        assertEquals(Precision.FP16, PrecisionPolicy.recommended(thresholdMode = true))
    }

    @Test fun recommended_combos_have_no_disclaimer() {
        assertEquals(AdviceLevel.NONE, PrecisionPolicy.advise(base, false, Precision.INT8).level)
        assertEquals(AdviceLevel.NONE, PrecisionPolicy.advise(base, true, Precision.FP16).level)
        assertEquals(AdviceLevel.NONE, PrecisionPolicy.advise(so400m, false, Precision.INT8).level)
    }

    @Test fun int8_in_threshold_mode_warns_parity_with_measured_numbers() {
        val a = PrecisionPolicy.advise(base, thresholdMode = true, Precision.INT8)
        assertEquals(AdviceLevel.WARN, a.level)
        assertTrue(a.text.contains("0.18"))
        assertTrue(a.text.contains("fp16"))
    }

    @Test fun so400m_fp32_warns_ram_regardless_of_mode() {
        val argmax = PrecisionPolicy.advise(so400m, thresholdMode = false, Precision.FP32)
        val thresh = PrecisionPolicy.advise(so400m, thresholdMode = true, Precision.FP32)
        assertEquals(AdviceLevel.WARN, argmax.level)
        assertEquals(AdviceLevel.WARN, thresh.level)
        assertTrue(argmax.text.contains("4.5 GB"))
    }

    @Test fun argmax_with_fp16_informs_no_benefit_over_int8() {
        val a = PrecisionPolicy.advise(base, thresholdMode = false, Precision.FP16)
        assertEquals(AdviceLevel.INFO, a.level)
        assertTrue(a.text.contains("int8"))
        assertTrue(a.text.contains("2.8×"))
    }

    @Test fun threshold_with_fp32_nonSo400m_informs_bitexact() {
        val a = PrecisionPolicy.advise(base, thresholdMode = true, Precision.FP32)
        assertEquals(AdviceLevel.INFO, a.level)
        assertTrue(a.text.contains("bit-exact"))
    }
}
