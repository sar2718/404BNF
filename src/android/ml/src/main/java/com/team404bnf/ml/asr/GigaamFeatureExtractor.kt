package com.team404bnf.ml.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU log-mel features for GigaAM v3 (onnx-asr 0.12.0, preprocessor gigaam_v3).
 * 16 kHz, periodic Hann 320 samples, hop 160, nFFT 320, 64 HTK mel bands;
 * filters and window are rounded to BF16 then stored as FP32, as in upstream.
 *
 * This first port prioritizes correctness over speed: DFT is O(N²) per frame.
 * It must be measured and compared with the Python reference before release.
 */
class GigaamFeatureExtractor {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 320
        const val HOP = 160
        const val MEL_BANDS = 64
        private const val FFT_BINS = FRAME_SIZE / 2 + 1
    }

    data class Features(val data: FloatArray, val frames: Int)

    private val hann = FloatArray(FRAME_SIZE) { i ->
        bf16((0.5 - 0.5 * cos(2.0 * PI * i / FRAME_SIZE)).toFloat())
    }
    private val filters = Array(FFT_BINS) { FloatArray(MEL_BANDS) }
    private val cosTable = Array(FFT_BINS) { k ->
        DoubleArray(FRAME_SIZE) { n -> cos(2.0 * PI * k * n / FRAME_SIZE) }
    }
    private val sinTable = Array(FFT_BINS) { k ->
        DoubleArray(FRAME_SIZE) { n -> sin(2.0 * PI * k * n / FRAME_SIZE) }
    }

    init {
        val maxMel = 2595.0 * log10(1.0 + 8000.0 / 700.0)
        val knots = DoubleArray(MEL_BANDS + 2) { i ->
            700.0 * (10.0.pow((maxMel * i / (MEL_BANDS + 1)) / 2595.0) - 1.0)
        }
        for (k in 0 until FFT_BINS) {
            val hz = k * SAMPLE_RATE.toDouble() / FRAME_SIZE
            for (m in 0 until MEL_BANDS) {
                val rise = (hz - knots[m]) / (knots[m + 1] - knots[m])
                val fall = (knots[m + 2] - hz) / (knots[m + 2] - knots[m + 1])
                // Upstream melscale_fbanks casts float64->bf16->float32.
                filters[k][m] = bf16(max(0.0, min(rise, fall)).toFloat())
            }
        }
    }

    fun extract(samples: FloatArray): Features {
        require(samples.size >= FRAME_SIZE) { "GigaAM requires at least 320 mono samples (20 ms)" }
        require(samples.size <= SAMPLE_RATE * 22) { "Process GigaAM in chunks <=22 s" }
        val frames = (samples.size - FRAME_SIZE) / HOP + 1
        val features = FloatArray(MEL_BANDS * frames)
        val powers = FloatArray(FFT_BINS)
        val windowed = FloatArray(FRAME_SIZE)
        for (t in 0 until frames) {
            val offset = t * HOP
            for (n in 0 until FRAME_SIZE) windowed[n] = samples[offset + n] * hann[n]
            for (k in 0 until FFT_BINS) {
                var re = 0.0
                var im = 0.0
                for (n in 0 until FRAME_SIZE) {
                    val s = windowed[n].toDouble()
                    re += s * cosTable[k][n]
                    im -= s * sinTable[k][n]
                }
                // Match NumPy: abs(complex FFT).astype(float32) ** 2.
                val magnitude = sqrt(re * re + im * im).toFloat()
                powers[k] = magnitude * magnitude
            }
            for (m in 0 until MEL_BANDS) {
                var energy = 0.0f
                for (k in 0 until FFT_BINS) energy += powers[k] * filters[k][m]
                features[m * frames + t] = ln(energy.coerceIn(1e-9f, 1e9f))
            }
        }
        return Features(features, frames)
    }
}

private fun bf16(value: Float): Float {
    // Round-to-nearest, ties-to-even, exactly as ml_dtypes.bfloat16 conversion.
    val bits = java.lang.Float.floatToRawIntBits(value)
    val rounded = bits + 0x7fff + ((bits ushr 16) and 1)
    return java.lang.Float.intBitsToFloat(rounded and -0x10000)
}
