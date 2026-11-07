package com.team404bnf.app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CapturedAudio(
    val relativePath: String,
    val durationMs: Long
)

class AudioCapture(
    private val context: Context
) {
    companion object {
        private const val SAMPLE_RATE = 16000
    }

    private enum class Command {
        RECORD, PAUSE, STOP
    }

    @Volatile
    private var command = Command.RECORD

    fun pause() {
        command = Command.PAUSE
    }

    fun resume() {
        command = Command.RECORD
    }

    fun stop() {
        command = Command.STOP
    }

    @SuppressLint("MissingPermission")
    suspend fun capture(
        recordingId: String,
        onDurationChanged: (Long) -> Unit
    ): CapturedAudio = withContext(Dispatchers.IO) {

        check(
            context.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            "Нет разрешения на микрофон"
        }

        val directory = File(
            context.filesDir,
            "recordings/$recordingId"
        )

        check(directory.isDirectory || directory.mkdirs()) {
            "Не удалось создать папку записи"
        }

        val pcmFile = File(directory, "audio.pcm.part")
        val wavFile = File(directory, "audio.wav")

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        check(minBuffer > 0) {
            "Устройство не поддерживает выбранный аудиоформат"
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 2, 3200)
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Не удалось инициализировать микрофон")
        }

        // 1600 отсчётов = 100 мс при 16 кГц
        val samples = ShortArray(1600)
        val bytes = ByteArray(samples.size * 2)

        var samplesWritten = 0L
        var microphoneStarted = false

        try {
            FileOutputStream(pcmFile).use { fileStream ->
                BufferedOutputStream(fileStream).use { output ->

                    while (command != Command.STOP) {

                        when (command) {
                            Command.RECORD -> {

                                if (!microphoneStarted) {
                                    recorder.startRecording()
                                    microphoneStarted = true
                                }

                                val count = recorder.read(
                                    samples,
                                    0,
                                    samples.size,
                                    AudioRecord.READ_BLOCKING
                                )

                                if (count < 0) {
                                    error("Ошибка чтения микрофона: $count")
                                }

                                if (count == 0) {
                                    delay(10)
                                    continue
                                }

                                if (command != Command.RECORD) {
                                    continue
                                }

                                // ShortArray -> PCM little-endian
                                for (i in 0 until count) {
                                    val value = samples[i].toInt()

                                    bytes[i * 2] = value.toByte()
                                    bytes[i * 2 + 1] =
                                        (value shr 8).toByte()
                                }

                                output.write(bytes, 0, count * 2)

                                samplesWritten += count

                                // Периодически сбрасываем данные на диск
                                if (
                                    samplesWritten % SAMPLE_RATE < count
                                ) {
                                    output.flush()
                                    fileStream.fd.sync()
                                }

                                onDurationChanged(
                                    samplesWritten * 1000 / SAMPLE_RATE
                                )
                            }

                            Command.PAUSE -> {

                                if (microphoneStarted) {
                                    recorder.stop()
                                    microphoneStarted = false
                                }

                                delay(40)
                            }

                            Command.STOP -> Unit
                        }
                    }
                }
            }
        } finally {
            if (microphoneStarted) {
                runCatching { recorder.stop() }
            }

            recorder.release()
        }

        check(samplesWritten > 0) {
            "Запись не содержит аудиоданных"
        }

        // Превращаем PCM в WAV
        createWav(pcmFile, wavFile)

        // PCM удаляем только после успешного создания WAV
        pcmFile.delete()

        CapturedAudio(
            relativePath = "recordings/$recordingId/audio.wav",
            durationMs = samplesWritten * 1000 / SAMPLE_RATE
        )
    }

    private fun createWav(
        pcmFile: File,
        wavFile: File
    ) {
        val dataSize = pcmFile.length()

        require(
            dataSize > 0 &&
                    dataSize <= Int.MAX_VALUE.toLong() - 36
        ) {
            "Неподдерживаемый размер WAV"
        }

        val header = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // Mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())

        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())

        val temporaryWav = File(
            wavFile.parentFile,
            "audio.wav.part"
        )

        FileOutputStream(temporaryWav).use { output ->
            output.write(header.array())

            pcmFile.inputStream().use { input ->
                input.copyTo(output, 32 * 1024)
            }

            output.fd.sync()
        }

        check(temporaryWav.renameTo(wavFile)) {
            "Не удалось завершить WAV-файл"
        }
    }
}