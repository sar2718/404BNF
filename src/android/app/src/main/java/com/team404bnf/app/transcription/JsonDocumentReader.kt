package com.team404bnf.app.transcription

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

object JsonDocumentReader {

    private const val MAX_BYTES = 20 * 1024 * 1024

    fun read(context: Context, uri: Uri): String {

        require(uri.scheme == "content") {
            "Неподдерживаемый источник документа"
        }

        val stream = context.contentResolver
            .openInputStream(uri)
            ?: throw IllegalArgumentException(
                "Не удалось открыть JSON-файл"
            )

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)

        stream.use { input ->

            while (true) {

                val count = input.read(buffer)

                if (count == -1) break
                if (count == 0) continue

                require(output.size() + count <= MAX_BYTES) {
                    "JSON превышает ограничение 20 МБ"
                }

                output.write(buffer, 0, count)
            }
        }

        require(output.size() > 0) {
            "Выбранный файл пуст"
        }

        return output.toString(Charsets.UTF_8.name())
    }
}