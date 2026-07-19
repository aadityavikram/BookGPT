package com.bookgpt.android.domain.retrieve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VectorQuantizerTest {
    @Test
    fun quantize_reducesStorageToOneBytePerDimension() {
        val vector = FloatArray(1536) { index -> (index % 17 - 8) / 10f }
        val quantized = VectorQuantizer.quantize(vector)
        assertEquals(1536, quantized.values.size)
        assertTrue(quantized.scale > 0f)
        assertTrue(quantized.norm > 0f)
    }

    @Test
    fun quantize_roundTripIsCloseToOriginal() {
        val vector = floatArrayOf(0.5f, -0.25f, 0.125f, 0f, 0.9f)
        val quantized = VectorQuantizer.quantize(vector)
        for (i in vector.indices) {
            val reconstructed = quantized.values[i].toInt() * quantized.scale
            assertTrue(abs(vector[i] - reconstructed) <= quantized.scale + 1e-5f)
        }
    }

    @Test
    fun l2Norm_matchesExpectedValue() {
        val vector = floatArrayOf(3f, 4f)
        assertEquals(5f, VectorQuantizer.l2Norm(vector), 1e-5f)
    }
}
