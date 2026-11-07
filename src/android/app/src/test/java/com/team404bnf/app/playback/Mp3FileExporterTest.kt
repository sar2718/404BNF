package com.team404bnf.app.playback

import de.sciss.jump3r.mp3.MP3Data
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib

import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class Mp3FileExporterTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun createWavFile(
        file: File,
        sampleRate: Int,
        channels: Int,
        sampleCount: Int,
        amplitude: Int = 16000
    ) {
        val dataSize = sampleCount * channels * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1.toShort()) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16.toShort())

        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (i in 0 until sampleCount * channels) {
            val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * amplitude).toInt().toShort()
            buffer.putShort(sample)
        }

        FileOutputStream(file).use { out ->
            out.write(buffer.array())
        }
    }

    @Test
    fun testExportMonoWavToMp3() = runBlocking {
        val wavFile = tempFolder.newFile("test_mono.wav")
        createWavFile(wavFile, 16000, 1, 16000)

        val mp3OutputStream = ByteArrayOutputStream()

        Mp3FileExporter.export(
            source = wavFile,
            outputStream = mp3OutputStream
        )

        val mp3Bytes = mp3OutputStream.toByteArray()
        assertTrue("MP3 file should not be empty", mp3Bytes.isNotEmpty())
        assertEquals(0xFF.toByte(), mp3Bytes[0])
        assertEquals(0xE0, mp3Bytes[1].toInt() and 0xE0)
    }

    @Test
    fun testExportStereoWavToMp3() = runBlocking {
        val wavFile = tempFolder.newFile("test_stereo.wav")
        createWavFile(wavFile, 44100, 2, 44100)

        val mp3OutputStream = ByteArrayOutputStream()

        Mp3FileExporter.export(
            source = wavFile,
            outputStream = mp3OutputStream
        )

        val mp3Bytes = mp3OutputStream.toByteArray()
        assertTrue("Stereo MP3 file should not be empty", mp3Bytes.isNotEmpty())
        assertEquals(0xFF.toByte(), mp3Bytes[0])
        assertEquals(0xE0, mp3Bytes[1].toInt() and 0xE0)
    }

    @Test
    fun testExportLongWavToMp3() = runBlocking {
        val wavFile = tempFolder.newFile("test_long.wav")
        // 5 seconds of audio
        createWavFile(wavFile, 16000, 1, 16000 * 5)

        val mp3OutputStream = ByteArrayOutputStream()

        Mp3FileExporter.export(
            source = wavFile,
            outputStream = mp3OutputStream
        )

        val mp3Bytes = mp3OutputStream.toByteArray()
        assertTrue("Long MP3 file should be non-empty", mp3Bytes.size > 10000)
        assertEquals(0xFF.toByte(), mp3Bytes[0])
        assertEquals(0xE0, mp3Bytes[1].toInt() and 0xE0)
    }

    @Test
    fun testEncodedMp3ContainsAudibleSignal() = runBlocking {
        val wavFile = tempFolder.newFile("test_signal.wav")
        val sampleRate = 16000
        val sampleCount = 16000 // 1 second
        val targetAmplitude = 16000

        createWavFile(
            file = wavFile,
            sampleRate = sampleRate,
            channels = 1,
            sampleCount = sampleCount,
            amplitude = targetAmplitude
        )

        val mp3OutputStream = ByteArrayOutputStream()
        Mp3FileExporter.export(
            source = wavFile,
            outputStream = mp3OutputStream
        )

        val mp3Bytes = mp3OutputStream.toByteArray()
        assertTrue("Encoded MP3 must not be empty", mp3Bytes.isNotEmpty())

        // Decode the generated MP3 back to PCM using MPGLib
        val decoder = MPGLib()
        val interfaceModule = Interface()
        val commonModule = Common()
        val vbrModule = de.sciss.jump3r.mp3.VBRTag()
        interfaceModule.setModules(vbrModule, commonModule)
        decoder.setModules(interfaceModule, commonModule)

        val handle = decoder.hip_decode_init()
        val pcmL = ShortArray(4096)
        val pcmR = ShortArray(4096)
        val mp3Data = MP3Data()

        var decodedSamplesCount = 0
        var maxAmplitude = 0

        var pos = 0
        val chunkSize = 512
        while (pos < mp3Bytes.size) {
            val len = minOf(chunkSize, mp3Bytes.size - pos)
            val chunk = mp3Bytes.copyOfRange(pos, pos + len)
            pos += len

            val samplesDecoded = decoder.hip_decode1_headers(
                handle,
                chunk,
                len,
                pcmL,
                pcmR,
                mp3Data
            )

            if (samplesDecoded > 0) {
                decodedSamplesCount += samplesDecoded
                for (i in 0 until samplesDecoded) {
                    val s = abs(pcmL[i].toInt())
                    maxAmplitude = max(maxAmplitude, s)
                }
            }
        }

        decoder.hip_decode_exit(handle)

        // 1. Audio sound is present (decoded sample count > 0)
        assertTrue("Decoded sample count should be > 0", decodedSamplesCount > 0)

        // 2. Absence of near-total silence (max amplitude should be significant, > 5000)
        assertTrue("Audio signal must not be silence (max amplitude = $maxAmplitude)", maxAmplitude > 5000)

        // 3. Amplitude approximately matches original (peak should be close to 16000)
        assertTrue("Peak amplitude should be close to original ($maxAmplitude vs $targetAmplitude)", maxAmplitude >= 12000)

        // 4. Approximate duration matching original (~16000 samples ± 3000 tolerance for LAME padding frames)
        assertTrue("Decoded sample count ($decodedSamplesCount) should match original ($sampleCount)", abs(decodedSamplesCount - sampleCount) < 3000)
    }

    @Test
    fun testPcm8BitScalingFormula() {
        // Unsigned 8-bit PCM 128 (silence) -> 0
        val silenceByte = 128.toByte()
        val silencePcm = ((silenceByte.toInt() and 0xFF) - 128) * 256
        assertEquals(0, silencePcm)

        // Unsigned 8-bit PCM 255 (max positive) -> 32512
        val maxByte = 255.toByte()
        val maxPcm = ((maxByte.toInt() and 0xFF) - 128) * 256
        assertEquals(32512, maxPcm)

        // Unsigned 8-bit PCM 0 (min negative) -> -32768
        val minByte = 0.toByte()
        val minPcm = ((minByte.toInt() and 0xFF) - 128) * 256
        assertEquals(-32768, minPcm)
    }

    @Test
    fun testCorruptedWavHeaderReturnsNull() {
        val corruptedFile = tempFolder.newFile("corrupted.wav")
        corruptedFile.writeBytes(ByteArray(100) { 0 })

        val decoder = WavAudioDecoder.create(corruptedFile)
        assertNull("Corrupted WAV header should return null decoder", decoder)
    }

    @Test
    fun testInvalidWavChannelsReturnsNull() {
        val multiChannelFile = tempFolder.newFile("multichannel.wav")
        // Create WAV header with 6 channels
        createWavFile(multiChannelFile, 16000, 6, 1000)

        val decoder = WavAudioDecoder.create(multiChannelFile)
        assertNull("WAV file with > 2 channels should return null from WavAudioDecoder", decoder)
    }

    @Test
    fun testExportMissingFileThrowsException() = runBlocking {
        val missingFile = File(tempFolder.root, "non_existent.wav")
        val mp3OutputStream = ByteArrayOutputStream()

        try {
            Mp3FileExporter.export(
                source = missingFile,
                outputStream = mp3OutputStream
            )
            fail("Should throw IOException for non-existent file")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("недоступен") == true)
        }
    }
}
