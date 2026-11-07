package com.team404bnf.ml.asr

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class GigaamM2Test {
    @Test fun `mel matches onnx-asr 0_12 BF16 reference for 440 Hz`() {
        val pcm = FloatArray(1600) { (0.7 * sin(2 * PI * 440 * it / 16000)).toFloat() }
        val feat = GigaamFeatureExtractor().extract(pcm)
        assertEquals(9, feat.frames)
        // Golden values computed from onnx-asr v0.12.0 NumPy GigaAM v3 frontend.
        val golden = mapOf(0 to -8.538925f, 4 to -6.007192f, 9 to 1.3163342f,
            10 to 6.132364f, 20 to -7.7220716f, 60 to -8.095676f)
        golden.forEach { (band, value) -> assertEquals("mel band $band", value, feat.data[band * feat.frames], 0.0001f) }
    }
    @Test fun `silence has finite log mel features`() {
        val result = GigaamFeatureExtractor().extract(FloatArray(320))
        assertEquals(1, result.frames)
        assertEquals(64, result.data.size)
        result.data.forEach { assertEquals(-20.723266f, it, 0.001f) }
    }
    @Test fun `split at quiet intervals and preserve all samples`() {
        val input = FloatArray(16_000 * 43) { 0.1f }
        for (i in 19 * 16_000 until 20 * 16_000) input[i] = 0f
        val chunks = QuietPointChunker.split(input)
        assertTrue(chunks.size >= 2)
        assertEquals(0, chunks[0].offsetSamples)
        assertEquals(input.size, chunks.sumOf { it.data.size })
        chunks.forEach { assertTrue(it.data.size <= 22 * 16_000) }
        for (i in 1 until chunks.size) assertEquals(
            chunks[i - 1].offsetSamples + chunks[i - 1].data.size, chunks[i].offsetSamples
        )
    }
    @Test fun `russian token space boundaries and approximate word times`() {
        val toks = listOf(
            RnntText.Emission(0, 0.0), RnntText.Emission(1, 0.1),
            RnntText.Emission(0, 0.4), RnntText.Emission(2, 0.5),
        )
        val decoded = RnntText.decode(toks, mapOf(0 to " ", 1 to "Привет", 2 to "мир"), 3.0, 2.0)
        assertEquals("Привет мир", decoded.text)
        assertEquals(2, decoded.words.size)
        assertEquals(3.1, decoded.words[0].startSeconds, 0.000001)
        assertEquals(3.5, decoded.words[1].startSeconds, 0.000001)
    }
    @Test fun `correct wav decoding and strict input requirements`() {
        fun wav(channels: Int): ByteArray {
            val bytes = ByteBuffer.allocate(44 + 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray())
            bytes.putInt(40)
            bytes.put("WAVEfmt ".toByteArray())
            bytes.putInt(16)
            bytes.putShort(1)
            bytes.putShort(channels.toShort())
            bytes.putInt(16000)
            bytes.putInt(16000 * channels * 2)
            bytes.putShort((channels * 2).toShort())
            bytes.putShort(16)
            bytes.put("data".toByteArray())
            bytes.putInt(4)
            bytes.putShort(16384)
            bytes.putShort((-16384).toShort())
            return bytes.array()
        }
        // Our reader deliberately rejects ultra-short inputs (<20ms).
        assertThrows(IllegalArgumentException::class.java) { Pcm16WavReader.read(ByteArrayInputStream(wav(1))) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16WavReader.read(ByteArrayInputStream(wav(2))) }
        assertThrows(EOFException::class.java) { Pcm16WavReader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
    }
}
