package com.team404bnf.app.localml

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.io.FileOutputStream

/** Converts local recordings/imported files into mono 16 kHz float PCM.
 * Writes output continuously to a temporary PCM file to support unlimited duration.
 */
internal object AudioToMono16k {
    private const val TARGET_RATE = 16_000

    suspend fun decode(file: File, pcmOut: File): Long = withContext(Dispatchers.IO) {
        require(file.isFile && file.canRead()) { "Аудиофайл отсутствует" }
        require(file.length() > 0L) { "Недопустимый размер аудиофайла" }

        var samples = decodeWav(file, pcmOut)
        if (samples == null) {
            samples = decodeWithMediaCodec(file, pcmOut)
        }
        samples
    }

    private suspend fun decodeWav(file: File, pcmOut: File): Long? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44L) return null
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var format = -1
            var channels = 0
            var rate = 0
            var bits = 0
            var frameBytes = 0
            var dataStart = -1L
            var dataLength = -1L
            while (raf.filePointer + 8L <= raf.length()) {
                currentCoroutineContext().ensureActive()
                val name = ByteArray(4).also { raf.readFully(it) }
                val size = readU32(raf)
                val start = raf.filePointer
                if (size > raf.length() - start) error("Повреждён WAV: неверная длина блока")
                when (String(name, Charsets.US_ASCII)) {
                    "fmt " -> {
                        require(size >= 16) { "Повреждён WAV: fmt" }
                        format = readU16(raf)
                        channels = readU16(raf)
                        rate = readU32(raf).toInt()
                        readU32(raf) // byte rate
                        frameBytes = readU16(raf)
                        bits = readU16(raf)
                    }
                    "data" -> if (dataStart < 0L) {
                        dataStart = start
                        dataLength = size
                    }
                }
                raf.seek(start + size + (size and 1L))
            }
            require(format == 1 || format == 3) { "WAV: поддерживаются PCM и Float32" }
            require(channels in 1..8 && rate in 8_000..192_000) { "WAV: неверные параметры аудио" }
            require((format == 1 && bits in setOf(8, 16, 24, 32)) ||
                (format == 3 && bits == 32)) { "WAV: неподдерживаемая глубина" }
            require(frameBytes == channels * (bits / 8) && dataStart >= 0L && dataLength > 0L) {
                "Повреждён WAV: данные отсутствуют"
            }
            val frames = dataLength / frameBytes
            require(frames > 0) { "Пустая аудиозапись" }

            raf.seek(dataStart)
            val chunkSize = 65536
            val mono = FloatArray(chunkSize)
            val frame = ByteArray(frameBytes)
            var framesRead = 0L

            FileOutputStream(pcmOut).channel.use { outChannel ->
                val resampler = Resampler(rate, TARGET_RATE, outChannel)
                while (framesRead < frames) {
                    currentCoroutineContext().ensureActive()
                    val toRead = minOf(chunkSize.toLong(), frames - framesRead).toInt()
                    for (i in 0 until toRead) {
                        raf.readFully(frame)
                        var sum = 0f
                        for (ch in 0 until channels) {
                            val off = ch * (bits / 8)
                            val s = when {
                                format == 3 -> Float.fromBits(
                                    (frame[off].toInt() and 255) or
                                    ((frame[off + 1].toInt() and 255) shl 8) or
                                    ((frame[off + 2].toInt() and 255) shl 16) or
                                    (frame[off + 3].toInt() shl 24))
                                bits == 8 -> ((frame[off].toInt() and 255) - 128) / 128f
                                bits == 16 -> {
                                    val n = (frame[off].toInt() and 255) or (frame[off + 1].toInt() shl 8)
                                    n.toShort() / 32768f
                                }
                                bits == 24 -> {
                                    val n = (frame[off].toInt() and 255) or
                                        ((frame[off + 1].toInt() and 255) shl 8) or
                                        (frame[off + 2].toInt() shl 16)
                                    n / 8388608f
                                }
                                else -> {
                                    val n = (frame[off].toInt() and 255) or
                                        ((frame[off + 1].toInt() and 255) shl 8) or
                                        ((frame[off + 2].toInt() and 255) shl 16) or
                                        (frame[off + 3].toInt() shl 24)
                                    n / 2147483648f
                                }
                            }
                            require(s.isFinite()) { "Повреждён WAV: нечисловые сэмплы" }
                            sum += s
                        }
                        mono[i] = (sum / channels).coerceIn(-1f, 1f)
                    }
                    resampler.process(mono, toRead)
                    framesRead += toRead
                }
                resampler.flush()
                return resampler.totalSamplesWritten
            }
        }
    }

    private suspend fun decodeWithMediaCodec(file: File, pcmOut: File): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле нет поддерживаемой аудиодорожки")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный аудиокодек")
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var outFormat = codec.outputFormat
            var sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT

            var inputDone = false
            var outputDone = false
            var lastProgress = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            val monoChunk = FloatArray(16384)

            FileOutputStream(pcmOut).channel.use { outChannel ->
                var resampler = Resampler(sampleRate, TARGET_RATE, outChannel)
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val b = codec.getInputBuffer(idx) ?: error("Нет входного буфера декодера")
                            b.clear()
                            val size = extractor.readSampleData(b, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(idx, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                            lastProgress = System.nanoTime()
                        }
                    }
                    when (val idx = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outFormat = codec.outputFormat
                            val newSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            if (newSampleRate != sampleRate) {
                                resampler.flush()
                                sampleRate = newSampleRate
                                resampler = Resampler(sampleRate, TARGET_RATE, outChannel)
                            }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (idx >= 0) {
                            if (info.size > 0) {
                                require(channels in 1..8 && sampleRate in 8_000..192_000) {
                                    "Некорректный формат декодированного аудио"
                                }
                                val b = codec.getOutputBuffer(idx) ?: error("Нет выходного буфера декодера")
                                b.order(ByteOrder.LITTLE_ENDIAN)
                                b.position(info.offset)
                                b.limit(info.offset + info.size)
                                val bytesPerSample = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> 2
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    else -> error("Декодер вернул неподдерживаемый PCM: $pcmEncoding")
                                }
                                require(info.size % (channels * bytesPerSample) == 0) { "Некорректный PCM-буфер" }
                                val frames = info.size / (channels * bytesPerSample)

                                var monoSize = 0
                                var monoArray = monoChunk
                                if (frames > monoChunk.size) monoArray = FloatArray(frames)

                                repeat(frames) {
                                    var sum = 0f
                                    repeat(channels) {
                                        sum += if (bytesPerSample == 2) b.short / 32768f else b.float
                                    }
                                    val value = sum / channels
                                    require(value.isFinite()) { "Некорректный PCM" }
                                    monoArray[monoSize++] = value.coerceIn(-1f, 1f)
                                }
                                resampler.process(monoArray, monoSize)
                            }
                            codec.releaseOutputBuffer(idx, false)
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            lastProgress = System.nanoTime()
                        }
                    }
                    require(System.nanoTime() - lastProgress < 30_000_000_000L) { "Декодер не отвечает" }
                }
                resampler.flush()
                return resampler.totalSamplesWritten
            }
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private class Resampler(
        val originalRate: Int,
        val targetRate: Int,
        val outChannel: FileChannel
    ) {
        private var inputTime = 0.0
        private var lastSample = 0f
        var totalSamplesWritten = 0L
        private val buf = ByteBuffer.allocateDirect(65536 * 4).order(ByteOrder.LITTLE_ENDIAN)

        fun process(chunk: FloatArray, size: Int) {
            if (size == 0) return
            if (originalRate == targetRate) {
                for (i in 0 until size) {
                    if (!buf.hasRemaining()) flush()
                    buf.putFloat(chunk[i])
                    totalSamplesWritten++
                }
                lastSample = chunk[size - 1]
                return
            }
            val step = originalRate.toDouble() / targetRate.toDouble()
            while (true) {
                val inputIndex = inputTime.toInt()
                if (inputIndex >= size) {
                    inputTime -= size
                    lastSample = chunk[size - 1]
                    break
                }
                val fraction = (inputTime - inputIndex).toFloat()
                val left = if (inputIndex == 0) lastSample else chunk[inputIndex - 1]
                val right = chunk[inputIndex]
                val v = left + (right - left) * fraction

                if (!buf.hasRemaining()) flush()
                buf.putFloat(v)
                totalSamplesWritten++
                inputTime += step
            }
        }

        fun flush() {
            buf.flip()
            while (buf.hasRemaining()) outChannel.write(buf)
            buf.clear()
        }
    }

    private fun readU16(raf: RandomAccessFile): Int =
        raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

    private fun readU32(raf: RandomAccessFile): Long =
        (readU16(raf).toLong() or (readU16(raf).toLong() shl 16)) and 0xffffffffL
}
