package com.team404bnf.ml.diar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Portable CPU port of Kaldi-native-fbank's 80-bin 16kHz frontend used by CAM++.
 * Hamming 25ms window, 10ms hop, DC removal, 0.97 preemphasis, 512-point FFT,
 * 80 mel bands, log power, no dither, no energy, mean-only CMN.
 *
 * A device/PC golden comparison against kaldi_native_fbank MUST still be run before
 * claiming equivalent embeddings or DER: this is an initial Kotlin implementation.
 */
class KaldiFbank80 {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 400
        const val FRAME_SHIFT = 160
        const val MEL_BINS = 80
        const val FFT_SIZE = 512
    }
    data class Features(val values: FloatArray, val frames: Int)
    private val window = DoubleArray(FRAME_SIZE) { 0.54 - 0.46 * cos(2.0 * PI * it / (FRAME_SIZE - 1)) }
    private val weights = Array(MEL_BINS) { DoubleArray(FFT_SIZE / 2 + 1) }

    init {
        fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)
        val low = mel(20.0)
        val span = (mel(8000.0) - low) / (MEL_BINS + 1)
        for (band in 0 until MEL_BINS) {
            val left = low + band * span
            val center = left + span
            val right = center + span
            for (bin in 0..FFT_SIZE / 2) {
                val current = mel(bin * SAMPLE_RATE.toDouble() / FFT_SIZE)
                weights[band][bin] = when {
                    current <= left || current >= right -> 0.0
                    current < center -> (current - left) / span
                    else -> (right - current) / span
                }
            }
        }
    }

    /** Return [1,frames,80] memory order as flat FloatArray. */
    fun extract(samples: FloatArray, applyCmn: Boolean = true): Features {
        require(samples.size >= 7200) { "CAM++ requires at least 0.45 seconds of speech" }
        require(samples.all(Float::isFinite))
        val frames = 1 + (samples.size - FRAME_SIZE) / FRAME_SHIFT
        require(frames in 10..40_000) { "Invalid FBank frame count" }
        val values = FloatArray(frames * MEL_BINS)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val powers = DoubleArray(FFT_SIZE / 2 + 1)
        for (f in 0 until frames) {
            val start = f * FRAME_SHIFT
            var dc = 0.0
            for (i in 0 until FRAME_SIZE) dc += samples[start + i].toDouble() * 32768.0
            dc /= FRAME_SIZE
            for (i in 0 until FRAME_SIZE) re[i] = samples[start + i] * 32768.0 - dc
            for (i in FRAME_SIZE - 1 downTo 1) re[i] -= 0.97 * re[i - 1]
            re[0] -= 0.97 * re[0]
            for (i in 0 until FRAME_SIZE) re[i] *= window[i]
            for (i in FRAME_SIZE until FFT_SIZE) re[i] = 0.0
            java.util.Arrays.fill(im, 0.0)
            fft(re, im)
            for (k in powers.indices) powers[k] = re[k] * re[k] + im[k] * im[k]
            for (band in 0 until MEL_BINS) {
                var energy = 0.0
                for (k in powers.indices) energy += weights[band][k] * powers[k]
                values[f * MEL_BINS + band] = ln(max(energy, 1.1920929e-7)).toFloat()
            }
        }
        // Kaldi mean-only CMN across all frames of the current embedding window.
        if (applyCmn) {
            for (band in 0 until MEL_BINS) {
                var total = 0.0
                for (frame in 0 until frames) total += values[frame * MEL_BINS + band]
                val mean = (total / frames).toFloat()
                for (frame in 0 until frames) values[frame * MEL_BINS + band] -= mean
            }
        }
        return Features(values, frames)
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == FFT_SIZE && im.size == n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tmpRe = re[i]; re[i] = re[j]; re[j] = tmpRe
                val tmpIm = im[i]; im[i] = im[j]; im[j] = tmpIm
            }
        }
        var length = 2
        while (length <= n) {
            val theta = -2.0 * PI / length
            val stepRe = cos(theta)
            val stepIm = sin(theta)
            for (base in 0 until n step length) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val even = base + k
                    val odd = even + length / 2
                    val tre = wr * re[odd] - wi * im[odd]
                    val tim = wr * im[odd] + wi * re[odd]
                    re[odd] = re[even] - tre; im[odd] = im[even] - tim
                    re[even] += tre; im[even] += tim
                    val nextRe = wr * stepRe - wi * stepIm
                    wi = wr * stepIm + wi * stepRe
                    wr = nextRe
                }
            }
            length = length shl 1
        }
    }
}
