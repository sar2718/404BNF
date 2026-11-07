package com.team404bnf.app.playback

import android.content.ContentResolver
import android.net.Uri

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

internal object WavFileExporter {

    suspend fun export(
        source: File,
        destination: Uri,
        resolver: ContentResolver
    ) = withContext(Dispatchers.IO) {

        if (!source.isFile || !source.canRead()) {
            throw IOException(
                "Аудиофайл недоступен"
            )
        }

        FileInputStream(source)
            .buffered()
            .use { input ->

                val header = ByteArray(12)

                try {
                    DataInputStream(input).readFully(header)
                } catch (e: IOException) {
                    throw IOException(
                        "Не удалось прочитать заголовок WAV",
                        e
                    )
                }

                val riff = String(
                    header,
                    0,
                    4,
                    Charsets.US_ASCII
                )

                val wave = String(
                    header,
                    8,
                    4,
                    Charsets.US_ASCII
                )

                if (riff != "RIFF" || wave != "WAVE") {
                    throw IOException(
                        "Файл не является поддерживаемым WAV"
                    )
                }

                val output = resolver.openOutputStream(
                    destination,
                    "wt"
                ) ?: throw IOException(
                    "Не удалось открыть файл для сохранения"
                )

                output.use { out ->

                    out.write(header)

                    val buffer = ByteArray(64 * 1024)

                    while (true) {

                        currentCoroutineContext().ensureActive()

                        val count = input.read(buffer)

                        if (count == -1) break

                        if (count > 0) {
                            out.write(buffer, 0, count)
                        }
                    }

                    out.flush()
                }
            }
    }
}