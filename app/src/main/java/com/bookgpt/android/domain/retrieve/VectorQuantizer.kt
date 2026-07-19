package com.bookgpt.android.domain.retrieve

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Asymmetric int8 quantization for on-device embedding storage.
 * Documents are stored as int8 + scale; queries stay float32 at search time.
 */
object VectorQuantizer {
    data class QuantizedVector(
        val values: ByteArray,
        val scale: Float,
        val norm: Float,
    )

    fun quantize(vector: FloatArray): QuantizedVector {
        var maxAbs = 0f
        var sumSquares = 0.0
        for (value in vector) {
            maxAbs = maxOf(maxAbs, abs(value))
            sumSquares += value.toDouble() * value.toDouble()
        }
        val scale = if (maxAbs == 0f) 1f else maxAbs / 127f
        val values = ByteArray(vector.size)
        for (i in vector.indices) {
            values[i] = (vector[i] / scale).roundToInt().coerceIn(-127, 127).toByte()
        }
        return QuantizedVector(
            values = values,
            scale = scale,
            norm = sqrt(sumSquares).toFloat(),
        )
    }

    fun l2Norm(vector: FloatArray): Float {
        var sumSquares = 0.0
        for (value in vector) {
            sumSquares += value.toDouble() * value.toDouble()
        }
        return sqrt(sumSquares).toFloat()
    }
}
