package com.example.clipcc.ui.classify

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelValidationTest {
    @Test fun trims_and_keeps_case() {
        val r = LabelValidation.validate(listOf("  Car ", "truck"), emptyList(), contrast = false)
        assertEquals(listOf("Car", "truck"), r.cleaned)
        assertEquals(null, r.error)
    }

    @Test fun rejects_all_blank() {
        val r = LabelValidation.validate(listOf("   ", ""), emptyList(), contrast = false)
        assertEquals("Add at least one label", r.error)
    }

    @Test fun rejects_duplicate_within_group() {
        val r = LabelValidation.validate(listOf("car", "car"), emptyList(), contrast = false)
        assertEquals("Duplicate label: car", r.error)
    }

    @Test fun contrast_requires_both_groups() {
        val r = LabelValidation.validate(listOf("car"), emptyList(), contrast = true)
        assertEquals("Contrast needs at least one positive and one negative label", r.error)
    }

    @Test fun contrast_rejects_cross_group_duplicate() {
        val r = LabelValidation.validate(listOf("car"), listOf("car"), contrast = true)
        assertEquals("Duplicate label: car", r.error)
    }

    @Test fun contrast_ok_returns_pos_then_neg_and_count() {
        val r = LabelValidation.validate(listOf("a", "b"), listOf("c"), contrast = true)
        assertEquals(listOf("a", "b", "c"), r.cleaned)
        assertEquals(2, r.posCount)
        assertEquals(null, r.error)
    }
}
