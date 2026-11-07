package com.team404bnf.ml.asr

import java.io.EOFException
import java.io.InputStream

/** Strict WAV PCM16 mono 16k reader for M2 debug. No silent resampling. */
object Pcm16WavReader {
    fun read(stream: InputStream, maxSeconds: Int = 300): FloatArray {
        val header = ByteArray(12)
        readFully(stream, header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "WAV RIFF required" }
        var fmtFound = false
        var samples: FloatArray? = null
        while (true) {
            val chunkHeader = ByteArray(8)
            if (!tryReadFully(stream, chunkHeader)) break
            val size = u32(chunkHeader, 4)
            require(size <= 64L * 1024 * 1024) { "WAV chunk is too large" }
            val tag = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            when (tag) {
                "fmt " -> {
                    require(size >= 16 && size <= 4096) { "Unsupported WAV fmt size" }
                    val info = ByteArray(size.toInt())
                    readFully(stream, info)
                    val codec = u16(info, 0)
                    val channels = u16(info, 2)
                    val sampleRate = u32(info, 4)
                    val bitsPerSample = u16(info, 14)
                    require(codec == 1 && channels == 1 && sampleRate == 16000L && bitsPerSample == 16) {
                        "M2 supports only PCM16 mono WAV 16000 Hz (got codec=$codec ch=$channels hz=$sampleRate bits=$bitsPerSample)"
                    }
                    fmtFound = true
                }
                "data" -> {
                    require(fmtFound) { "WAV fmt chunk must precede data" }
                    require(size % 2L == 0L && size >= 640) { "Invalid PCM16 data" }
                    require(size <= maxSeconds * 32000L) { "Recording exceeds M2 debug ${maxSeconds}s limit" }
                    val bytes = ByteArray(size.toInt())
                    readFully(stream, bytes)
                    samples = FloatArray(bytes.size / 2) { index ->
                        val value = u16(bytes, index * 2).toShort()
                        value / 32768.0f
                    }
                    break
                }
                else -> skipExactly(stream, size)
            }
            if ((size and 1L) != 0L) skipExactly(stream, 1)
        }
        return requireNotNull(samples) { "No audio data found in WAV" }
    }
    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = ((u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)) and 0xffff_ffffL
    private fun tryReadFully(s: InputStream, bytes: ByteArray): Boolean {
        val first = s.read()
        if (first == -1) return false
        bytes[0] = first.toByte()
        for (i in 1 until bytes.size) { val v = s.read(); if (v < 0) throw EOFException(); bytes[i] = v.toByte() }
        return true
    }
    private fun readFully(s: InputStream, bytes: ByteArray) {
        var done = 0
        while (done < bytes.size) {
            val read = s.read(bytes, done, bytes.size - done)
            if (read < 0) throw EOFException("Unexpected end of WAV")
            if (read == 0) continue
            done += read
        }
    }
    private fun skipExactly(s: InputStream, size: Long) {
        var remaining = size
        val temp = ByteArray(8192)
        while (remaining > 0) {
            val n = s.read(temp, 0, minOf(remaining, temp.size.toLong()).toInt())
            if (n < 0) throw EOFException("WAV chunk truncated")
            remaining -= n
        }
    }
}
