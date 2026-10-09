package com.unishare.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentThumbnailTest {
    @Test
    fun large_source_is_power_of_two_sampled_without_decoding_full_resolution() {
        assertEquals(8, calculateThumbnailSampleSize(8_000, 4_000, 1_000, 500))
    }

    @Test
    fun source_within_preview_bounds_is_not_upscaled() {
        assertEquals(1, calculateThumbnailSampleSize(800, 450, 1_000, 500))
    }
}
