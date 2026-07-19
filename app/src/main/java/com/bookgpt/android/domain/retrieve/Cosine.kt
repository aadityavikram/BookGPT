package com.bookgpt.android.domain.retrieve

import kotlin.math.sqrt

object Cosine {
    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    /** Cosine similarity when the query L2 norm is already known. */
    fun similarityToFloat(
        query: FloatArray,
        queryNorm: Float,
        document: FloatArray,
    ): Float {
        require(query.size == document.size) {
            "Vector size mismatch: ${query.size} vs ${document.size}"
        }
        if (queryNorm == 0f) return 0f
        var dot = 0.0
        var docNormSq = 0.0
        for (i in query.indices) {
            val q = query[i].toDouble()
            val d = document[i].toDouble()
            dot += q * d
            docNormSq += d * d
        }
        if (docNormSq == 0.0) return 0f
        return (dot / (queryNorm * sqrt(docNormSq))).toFloat()
    }

    /**
     * Cosine similarity against an int8-quantized document vector.
     * Reconstructs document components as `quantized[i] * scale`.
     */
    fun similarityToQuantized(
        query: FloatArray,
        queryNorm: Float,
        quantized: ByteArray,
        scale: Float,
        documentNorm: Float,
    ): Float {
        require(query.size == quantized.size) {
            "Vector size mismatch: ${query.size} vs ${quantized.size}"
        }
        if (queryNorm == 0f || documentNorm == 0f || scale == 0f) return 0f
        var dot = 0.0
        for (i in query.indices) {
            dot += query[i].toDouble() * (quantized[i].toInt() * scale)
        }
        return (dot / (queryNorm * documentNorm)).toFloat()
    }

    /** Convert cosine similarity to a distance comparable to Chroma's cosine distance. */
    fun distanceFromSimilarity(similarity: Float): Float = 1f - similarity
}
