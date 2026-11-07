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
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Converts local recordings/imported files into the exact mono 16 kHz float PCM used by M2/M3.
 * Does not upload audio or retain an additional decoded copy on disk.
 */
internal object AudioToMono16k {
    private const val TARGET_RATE = 16_000
    private const val MAX_SECONDS = 300
    private const val MAX_SAMPLES = TARGET_RATE * MAX_SECONDS

    suspend fun decode(file: File): FloatArray = withContext(Dispatchers.IO) {
        require(file.isFile && file.canRead()) { "Аудиофайл отсутствует" }
        require(file.length() > 0L && file.length() <= 1024L * 1024L * 1024L) {
            "Недопустимый размер аудиофайла"
        }
        // Our microphone recordings are WAV; this branch also accepts WAV 44.1/48 kHz and stereo.
        decodeWav(file) ?: decodeWithMediaCodec(file)
    }

    private suspend fun decodeWav(file: File): FloatArray? {
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
            require(frames > 0 && frames * 1.0 / rate <= MAX_SECONDS) {
                "Максимальная длительность записи — 5 минут"
            }
            val mono = FloatArray(frames.toInt())
            val frame = ByteArray(frameBytes)
            raf.seek(dataStart)
            for (i in mono.indices) {
                if ((i and 8191) == 0) currentCoroutineContext().ensureActive()
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
            return resample(mono, rate)
        }
    }

    /** MP3/M4A/AAC/OGG/FLAC/AMR/etc are decoded by the device's native codecs. */
    private suspend fun decodeWithMediaCodec(file: File): FloatArray {
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
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            require(durationUs <= MAX_SECONDS * 1_000_000L) { "Максимальная длительность записи — 5 минут" }
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
            val pcm = FloatCollector()
            var inputDone = false
            var outputDone = false
            var lastProgress = System.nanoTime()
            val info = MediaCodec.BufferInfo()
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
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        require(pcm.isEmpty) { "Декодер изменил параметры аудио посреди записи" }
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
                            require(pcm.size + frames <= sampleRate * MAX_SECONDS) { "Аудио длиннее 5 минут" }
                            repeat(frames) {
                                var sum = 0f
                                repeat(channels) {
                                    sum += if (bytesPerSample == 2) b.short / 32768f else b.float
                                }
                                val value = sum / channels
                                require(value.isFinite()) { "Некорректный PCM" }
                                pcm.add(value.coerceIn(-1f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        lastProgress = System.nanoTime()
                    }
                }
                require(System.nanoTime() - lastProgress < 30_000_000_000L) { "Декодер не отвечает" }
            }
            require(!pcm.isEmpty) { "После декодирования нет аудиоданных" }
            return resample(pcm.toArray(), sampleRate)
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private suspend fun resample(input: FloatArray, originalRate: Int): FloatArray {
        require(input.isNotEmpty()) { "Пустая аудиозапись" }
        if (originalRate == TARGET_RATE) return input
        val n = (input.size.toDouble() * TARGET_RATE / originalRate).roundToInt()
        require(n in 1..MAX_SAMPLES) { "Максимальная длительность записи — 5 минут" }
        val output = FloatArray(n)
        for (i in output.indices) {
            if ((i and 8191) == 0) currentCoroutineContext().ensureActive()
            val pos = i.toDouble() * originalRate / TARGET_RATE
            val left = pos.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (pos - left).toFloat().coerceIn(0f, 1f)
            output[i] = input[left] + (input[right] - input[left]) * fraction
        }
        return output
    }

    private fun readU16(raf: RandomAccessFile): Int =
        raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

    private fun readU32(raf: RandomAccessFile): Long =
        (readU16(raf).toLong() or (readU16(raf).toLong() shl 16)) and 0xffffffffL

    private class FloatCollector {
        private var values = FloatArray(16_384)
        var size: Int = 0
            private set
        val isEmpty: Boolean get() = size == 0
        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }
        fun toArray(): FloatArray = values.copyOf(size)
    }
}
