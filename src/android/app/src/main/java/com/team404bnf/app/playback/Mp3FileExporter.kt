package com.team404bnf.app.playback

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.PsyModel
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.MPGLib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface AudioDecoder : AutoCloseable {
    val sampleRate: Int
    val channelCount: Int
    suspend fun readFrames(left: IntArray, right: IntArray, maxFrames: Int): Int
}

internal class WavAudioDecoder private constructor(
    private val inputStream: DataInputStream,
    override val sampleRate: Int,
    override val channelCount: Int,
    private val totalPcmBytes: Long
) : AudioDecoder {

    private var bytesRead: Long = 0L

    override suspend fun readFrames(left: IntArray, right: IntArray, maxFrames: Int): Int {
        val bytesPerFrame = channelCount * 2
        val remainingBytes = totalPcmBytes - bytesRead
        if (remainingBytes <= 0) return 0

        val maxAvailableFrames = (remainingBytes / bytesPerFrame).toInt()
        val framesToRead = minOf(maxFrames, maxAvailableFrames)
        if (framesToRead <= 0) return 0

        val bytesToRead = framesToRead * bytesPerFrame
        val buffer = ByteArray(bytesToRead)
        inputStream.readFully(buffer)
        bytesRead += bytesToRead

        if (channelCount == 1) {
            for (i in 0 until framesToRead) {
                val low = buffer[i * 2].toInt() and 0xFF
                val high = buffer[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                left[i] = sample
                right[i] = sample
            }
        } else {
            for (i in 0 until framesToRead) {
                val lLow = buffer[i * 4].toInt() and 0xFF
                val lHigh = buffer[i * 4 + 1].toInt()
                val leftSample = ((lHigh shl 8) or lLow).toShort().toInt()

                val rLow = buffer[i * 4 + 2].toInt() and 0xFF
                val rHigh = buffer[i * 4 + 3].toInt()
                val rightSample = ((rHigh shl 8) or rLow).toShort().toInt()

                left[i] = leftSample
                right[i] = rightSample
            }
        }

        return framesToRead
    }

    override fun close() {
        runCatching { inputStream.close() }
    }

    companion object {
        fun create(file: File): WavAudioDecoder? {
            val fileLength = file.length()
            if (fileLength < 44) return null

            val fileStream = runCatching { FileInputStream(file).buffered() }.getOrNull() ?: return null
            val dataInput = DataInputStream(fileStream)

            try {
                val riffHeader = ByteArray(12)
                dataInput.readFully(riffHeader)

                val riff = String(riffHeader, 0, 4, Charsets.US_ASCII)
                val wave = String(riffHeader, 8, 4, Charsets.US_ASCII)

                if (riff != "RIFF" || wave != "WAVE") {
                    dataInput.close()
                    return null
                }

                var audioFormat = -1
                var channels = -1
                var sampleRate = -1
                var bitsPerSample = -1
                var dataSize = -1L

                while (true) {
                    val chunkHeader = ByteArray(8)
                    try {
                        dataInput.readFully(chunkHeader)
                    } catch (_: Exception) {
                        break
                    }

                    val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int.toLong() and 0xFFFFFFFFL

                    if (chunkSize > fileLength) {
                        dataInput.close()
                        return null
                    }

                    if (chunkId == "fmt ") {
                        if (chunkSize < 16 || chunkSize > 4096) {
                            dataInput.close()
                            return null
                        }
                        val fmtData = ByteArray(chunkSize.toInt())
                        dataInput.readFully(fmtData)
                        val buf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = buf.short.toInt() and 0xFFFF
                        channels = buf.short.toInt() and 0xFFFF
                        sampleRate = buf.int
                        buf.int // byteRate
                        buf.short // blockAlign
                        bitsPerSample = buf.short.toInt() and 0xFFFF
                    } else if (chunkId == "data") {
                        dataSize = chunkSize
                        break
                    } else {
                        var skip = chunkSize
                        if (skip % 2 != 0L) skip++
                        if (skip > fileLength) {
                            dataInput.close()
                            return null
                        }
                        val skipped = dataInput.skipBytes(skip.toInt())
                        if (skipped.toLong() != skip) {
                            dataInput.close()
                            return null
                        }
                    }
                }

                if (audioFormat != 1 ||
                    channels !in 1..2 ||
                    sampleRate !in 8000..192000 ||
                    bitsPerSample != 16 ||
                    dataSize <= 0
                ) {
                    dataInput.close()
                    return null
                }

                return WavAudioDecoder(
                    inputStream = dataInput,
                    sampleRate = sampleRate,
                    channelCount = channels,
                    totalPcmBytes = dataSize
                )
            } catch (_: Exception) {
                runCatching { dataInput.close() }
                return null
            }
        }
    }
}

internal class MediaCodecAudioDecoder(
    sourceFile: File
) : AudioDecoder {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    override val sampleRate: Int
    override val channelCount: Int

    private var sawInputEOS = false
    private var sawOutputEOS = false

    private var pendingLeft = IntArray(0)
    private var pendingRight = IntArray(0)
    private var pendingOffset = 0
    private var pendingCount = 0

    init {
        val ext = MediaExtractor()
        extractor = ext

        try {
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            FileInputStream(sourceFile).use { fis ->
                ext.setDataSource(fis.fd)
            }

            for (i in 0 until ext.trackCount) {
                val trackFormat = ext.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            check(audioTrackIndex >= 0 && format != null) {
                "Не удалось найти аудиодорожку"
            }

            ext.selectTrack(audioTrackIndex)

            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            if (channelCount !in 1..2) {
                throw IOException("Неподдерживаемое количество каналов: $channelCount. Поддерживаются только моно (1) и стерео (2).")
            }

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Неизвестный формат аудио")

            val cd = MediaCodec.createDecoderByType(mime)
            codec = cd
            cd.configure(format, null, null, 0)
            cd.start()

        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private var lastProgressTimeMs = 0L

    override suspend fun readFrames(left: IntArray, right: IntArray, maxFrames: Int): Int {
        val cd = codec ?: return 0
        val ext = extractor ?: return 0
        var framesWritten = 0

        val now = SystemClock.elapsedRealtime()
        if (lastProgressTimeMs == 0L) {
            lastProgressTimeMs = now
        }

        while (framesWritten < maxFrames) {
            currentCoroutineContext().ensureActive()

            if (pendingCount > 0) {
                val toCopy = minOf(maxFrames - framesWritten, pendingCount)
                System.arraycopy(pendingLeft, pendingOffset, left, framesWritten, toCopy)
                System.arraycopy(pendingRight, pendingOffset, right, framesWritten, toCopy)
                pendingOffset += toCopy
                pendingCount -= toCopy
                framesWritten += toCopy
                lastProgressTimeMs = SystemClock.elapsedRealtime()
                if (framesWritten == maxFrames) {
                    return framesWritten
                }
            }

            if (sawOutputEOS) {
                return framesWritten
            }

            var progressMade = false

            if (!sawInputEOS) {
                val inputIndex = cd.dequeueInputBuffer(10000L)
                if (inputIndex >= 0) {
                    val inputBuffer = cd.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = ext.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            sawInputEOS = true
                            cd.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            val timeUs = ext.sampleTime
                            cd.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                timeUs,
                                0
                            )
                            ext.advance()
                        }
                        progressMade = true
                    }
                }
            }

            val info = MediaCodec.BufferInfo()
            val outputIndex = cd.dequeueOutputBuffer(info, 10000L)

            if (outputIndex >= 0) {
                progressMade = true
                val outputBuffer = cd.getOutputBuffer(outputIndex)
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)

                    val outputFormat = cd.outputFormat
                    val pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }

                    val decodedFrames = decodeBufferToPending(outputBuffer, info.size, pcmEncoding)
                    pendingOffset = 0
                    pendingCount = decodedFrames
                }
                cd.releaseOutputBuffer(outputIndex, false)

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                progressMade = true
                val newFormat = cd.outputFormat
                val outSampleRate = if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else sampleRate

                val outChannels = if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else channelCount

                if (outChannels !in 1..2) {
                    throw IOException("Неподдерживаемое количество каналов в выходном формате декодера: $outChannels")
                }

                if (outSampleRate != sampleRate || outChannels != channelCount) {
                    throw IOException(
                        "Выходной формат декодера ($outSampleRate Гц, $outChannels кан.) " +
                            "не совпадает с настроенным ($sampleRate Гц, $channelCount кан.)"
                    )
                }
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!sawInputEOS && framesWritten > 0) {
                    break
                }
            }

            val loopNow = SystemClock.elapsedRealtime()
            if (progressMade) {
                lastProgressTimeMs = loopNow
            } else {
                if (loopNow - lastProgressTimeMs >= 10000L) {
                    throw IOException("Декодер аудиозаписи не отвечает (превышено время ожидания 10 сек)")
                }
            }
        }

        return framesWritten
    }

    private fun decodeBufferToPending(buffer: ByteBuffer, sizeBytes: Int, encoding: Int): Int {
        val byteBuf = buffer.order(ByteOrder.LITTLE_ENDIAN)

        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val totalFloats = sizeBytes / 4
                val frames = totalFloats / channelCount
                if (pendingLeft.size < frames) {
                    pendingLeft = IntArray(frames)
                    pendingRight = IntArray(frames)
                }
                val floatBuf = byteBuf.asFloatBuffer()
                val floatArray = FloatArray(totalFloats)
                floatBuf.get(floatArray)

                if (channelCount == 1) {
                    for (i in 0 until frames) {
                        val sample = (floatArray[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
                        pendingLeft[i] = sample
                        pendingRight[i] = sample
                    }
                } else {
                    for (i in 0 until frames) {
                        val leftSample = (floatArray[i * 2].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
                        val rightSample = (floatArray[i * 2 + 1].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
                        pendingLeft[i] = leftSample
                        pendingRight[i] = rightSample
                    }
                }
                frames
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                val totalBytes = sizeBytes
                val frames = totalBytes / channelCount
                if (pendingLeft.size < frames) {
                    pendingLeft = IntArray(frames)
                    pendingRight = IntArray(frames)
                }
                val startPos = byteBuf.position()

                if (channelCount == 1) {
                    for (i in 0 until frames) {
                        val sample = ((byteBuf.get(startPos + i).toInt() and 0xFF) - 128) * 256
                        pendingLeft[i] = sample
                        pendingRight[i] = sample
                    }
                } else {
                    for (i in 0 until frames) {
                        val leftSample = ((byteBuf.get(startPos + i * 2).toInt() and 0xFF) - 128) * 256
                        val rightSample = ((byteBuf.get(startPos + i * 2 + 1).toInt() and 0xFF) - 128) * 256
                        pendingLeft[i] = leftSample
                        pendingRight[i] = rightSample
                    }
                }
                frames
            }

            AudioFormat.ENCODING_PCM_16BIT -> {
                val totalShorts = sizeBytes / 2
                val frames = totalShorts / channelCount
                if (pendingLeft.size < frames) {
                    pendingLeft = IntArray(frames)
                    pendingRight = IntArray(frames)
                }
                val shortBuf = byteBuf.asShortBuffer()
                val shortArray = ShortArray(totalShorts)
                shortBuf.get(shortArray)

                if (channelCount == 1) {
                    for (i in 0 until frames) {
                        val sample = shortArray[i].toInt()
                        pendingLeft[i] = sample
                        pendingRight[i] = sample
                    }
                } else {
                    for (i in 0 until frames) {
                        val leftSample = shortArray[i * 2].toInt()
                        val rightSample = shortArray[i * 2 + 1].toInt()
                        pendingLeft[i] = leftSample
                        pendingRight[i] = rightSample
                    }
                }
                frames
            }

            else -> throw IOException("Неподдерживаемое кодирование PCM: $encoding")
        }
    }

    override fun close() {
        codec?.let { runCatching { it.stop() } }
        codec?.let { runCatching { it.release() } }
        codec = null
        extractor?.let { runCatching { it.release() } }
        extractor = null
    }
}

internal object Mp3FileExporter {

    private fun createLameInstance(): Pair<Lame, LameGlobalFlags> {
        val lame = Lame()
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val mpglib = MPGLib()
        val rv = Reservoir()
        val tak = Takehiro()
        val psy = PsyModel()

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpglib)
        bs.setModules(ga, mpglib, ver, vbr)
        p.setModules(lame)
        qupvt.setModules(tak, rv, psy)
        qu.setModules(bs, rv, qupvt, tak)
        vbr.setModules(lame, bs, ver)
        id3.setModules(bs, ver)
        rv.setModules(bs)
        tak.setModules(qupvt)

        val gfp = lame.lame_init()
        return Pair(lame, gfp)
    }

    suspend fun export(
        source: File,
        destination: Uri,
        resolver: ContentResolver
    ) = withContext(Dispatchers.IO) {

        val outputStream = resolver.openOutputStream(destination, "wt")
            ?: throw IOException("Не удалось открыть файл для сохранения")

        outputStream.use { out ->
            export(
                source = source,
                outputStream = out
            )
        }
    }

    suspend fun export(
        source: File,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {

        if (!source.isFile || !source.canRead()) {
            throw IOException("Аудиофайл недоступен")
        }

        val wavDecoder = WavAudioDecoder.create(source)
        val decoder: AudioDecoder = wavDecoder ?: runCatching {
            MediaCodecAudioDecoder(source)
        }.getOrElse { e ->
            throw IOException("Не удалось декодировать аудиозапись", e)
        }

        decoder.use { audioDecoder ->
            val (lame, gfp) = createLameInstance()

            try {
                val sampleRate = audioDecoder.sampleRate
                val channels = audioDecoder.channelCount

                gfp.in_samplerate = sampleRate
                gfp.out_samplerate = sampleRate
                gfp.num_channels = if (channels >= 2) 2 else 1
                gfp.mode = if (channels >= 2) MPEGMode.STEREO else MPEGMode.MONO
                gfp.brate = 128
                gfp.quality = 5
                gfp.write_id3tag_automatic = false
                gfp.bWriteVbrTag = false

                val initRes = lame.lame_init_params(gfp)
                if (initRes < 0) {
                    throw IOException("Не удалось инициализировать MP3-кодировщик: код $initRes")
                }

                val chunkSize = 4096
                val leftBuffer = IntArray(chunkSize)
                val rightBuffer = IntArray(chunkSize)
                val scaledLeft = IntArray(chunkSize)
                val scaledRight = IntArray(chunkSize)
                val mp3Buffer = ByteArray(16384)

                while (true) {
                    currentCoroutineContext().ensureActive()

                    val readCount = audioDecoder.readFrames(leftBuffer, rightBuffer, chunkSize)
                    if (readCount <= 0) break

                    // Scale 16-bit PCM integer samples (-32768..32767) to 32-bit for lame_encode_buffer_int
                    for (i in 0 until readCount) {
                        scaledLeft[i] = leftBuffer[i] shl 16
                        scaledRight[i] = rightBuffer[i] shl 16
                    }

                    val encoded = lame.lame_encode_buffer_int(
                        gfp,
                        scaledLeft,
                        scaledRight,
                        readCount,
                        mp3Buffer,
                        0,
                        mp3Buffer.size
                    )

                    if (encoded < 0) {
                        throw IOException("Ошибка кодирования MP3-буфера: код $encoded")
                    }

                    if (encoded > 0) {
                        outputStream.write(mp3Buffer, 0, encoded)
                    }
                }

                val flushed = lame.lame_encode_flush(
                    gfp,
                    mp3Buffer,
                    0,
                    mp3Buffer.size
                )

                if (flushed < 0) {
                    throw IOException("Ошибка завершения кодирования MP3: код $flushed")
                }

                if (flushed > 0) {
                    outputStream.write(mp3Buffer, 0, flushed)
                }

                outputStream.flush()
            } finally {
                runCatching { lame.lame_close(gfp) }
            }
        }
    }
}
