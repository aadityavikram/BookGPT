package com.bookgpt.android.domain.retrieve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosineTest {
    @Test
    fun identicalVectors_haveSimilarityOneAndDistanceZero() {
        val vector = floatArrayOf(0.2f, 0.4f, 0.8f)
        val similarity = Cosine.similarity(vector, vector)
        assertEquals(1f, similarity, 1e-5f)
        assertEquals(0f, Cosine.distanceFromSimilarity(similarity), 1e-5f)
    }

    @Test
    fun orthogonalVectors_haveNearZeroSimilarity() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val similarity = Cosine.similarity(a, b)
        assertEquals(0f, similarity, 1e-5f)
        assertEquals(1f, Cosine.distanceFromSimilarity(similarity), 1e-5f)
    }

    @Test
    fun ranksCloserVectorHigher() {
        val query = floatArrayOf(1f, 0f)
        val near = floatArrayOf(0.9f, 0.1f)
        val far = floatArrayOf(0f, 1f)
        assertTrue(Cosine.similarity(query, near) > Cosine.similarity(query, far))
    }

    @Test
    fun quantizedSimilarity_tracksFloatSimilarity() {
        val query = floatArrayOf(0.4f, 0.2f, 0.8f, -0.1f)
        val document = floatArrayOf(0.35f, 0.25f, 0.75f, -0.05f)
        val expected = Cosine.similarity(query, document)
        val quantized = VectorQuantizer.quantize(document)
        val actual = Cosine.similarityToQuantized(
            query = query,
            queryNorm = VectorQuantizer.l2Norm(query),
            quantized = quantized.values,
            scale = quantized.scale,
            documentNorm = quantized.norm,
        )
        assertEquals(expected, actual, 0.02f)
    }

    @Test
    fun quantizedSimilarity_preservesRelativeRanking() {
        val query = floatArrayOf(1f, 0.2f, 0f)
        val near = floatArrayOf(0.95f, 0.25f, 0.05f)
        val far = floatArrayOf(0f, 0f, 1f)
        val queryNorm = VectorQuantizer.l2Norm(query)
        val nearQ = VectorQuantizer.quantize(near)
        val farQ = VectorQuantizer.quantize(far)
        val nearSim = Cosine.similarityToQuantized(
            query, queryNorm, nearQ.values, nearQ.scale, nearQ.norm,
        )
        val farSim = Cosine.similarityToQuantized(
            query, queryNorm, farQ.values, farQ.scale, farQ.norm,
        )
        assertTrue(nearSim > farSim)
    }
}
