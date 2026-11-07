package com.team404bnf.ml

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Progress is sent on the IO thread; UI callers must dispatch updates to main thread. */
sealed interface ModelEvent {
    data class Verified(val asset: ModelAsset) : ModelEvent
    data class Downloading(val asset: ModelAsset, val downloaded: Long, val total: Long?) : ModelEvent
    data class Installed(val asset: ModelAsset) : ModelEvent
}

/**
 * Installs only pinned/verified model files in private persistent storage (filesDir).
 * No downloads happen in the constructor or during inference. Call ensureInstalled()
 * explicitly on first-run setup with user consent and a network connection.
 */
class ModelManager(context: Context) {
    private val root = File(context.applicationContext.filesDir, "models")
    private val installLock = Mutex()

    fun pathFor(asset: ModelAsset): File {
        require(ModelCatalog.assets.contains(asset)) { "Unknown model asset" }
        return File(root, asset.relativePath)
    }

    /** Verify hashes on IO dispatcher; potentially scans ~1 GB for GigaAM. */
    suspend fun verifyInstalled(): List<String> = withContext(Dispatchers.IO) {
        installLock.withLock {
            ModelCatalog.assets.mapNotNull { asset ->
                val error = validationError(pathFor(asset), asset)
                if (error == null) null else "${asset.relativePath}: $error"
            }
        }
    }

    suspend fun ensureInstalled(onEvent: (ModelEvent) -> Unit = {}) = withContext(Dispatchers.IO) {
        installLock.withLock {
            root.mkdirs()
            require(root.isDirectory) { "Cannot create model directory: $root" }
            for (asset in ModelCatalog.assets) {
                currentCoroutineContext().ensureActive()
                val target = pathFor(asset)
                if (target.exists()) {
                    val reason = validationError(target, asset)
                    // Never silently replace an existing damaged/untrusted model.
                    if (reason != null) error("Invalid existing model ${asset.relativePath}: $reason. " +
                        "Delete this specific file to redownload it.")
                    onEvent(ModelEvent.Verified(asset))
                } else {
                    downloadAsset(target, asset, onEvent)
                    onEvent(ModelEvent.Installed(asset))
                }
            }
        }
    }

    /** Used only after successful verification; one should not rely on file existence alone. */
    suspend fun checkedFile(asset: ModelAsset): File = withContext(Dispatchers.IO) {
        val file = pathFor(asset)
        val error = validationError(file, asset)
        check(error == null) { "Model ${asset.relativePath} is not ready: $error" }
        file
    }

    private fun validationError(file: File, asset: ModelAsset): String? {
        if (!file.isFile) return "missing file"
        if (file.length() !in asset.minBytes..asset.maxBytes) return "invalid size (${file.length()} bytes)"
        val expected = asset.expectedSha256
        if (expected != null && sha256(file) != expected) return "SHA-256 mismatch"
        // GigaAM's small metadata files are pinned by immutable commit URL but do not
        // have separate upstream hashes here; only non-empty / size checks are done.
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun downloadAsset(
        target: File,
        asset: ModelAsset,
        onEvent: (ModelEvent) -> Unit,
    ) {
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val part = File(target.parentFile, "${target.name}.part")
        if (part.exists() && (part.length() == 0L || part.length() > asset.maxBytes)) {
            check(part.delete()) { "Cannot reset partial download: $part" }
        }
        // A large FP32 encoder needs substantial *persistent disk* space. No INT8 fallback.
        val reserve = if (asset.minBytes >= 500_000_000L) {
            (1_000_000_000L - (if (part.exists()) part.length() else 0L)).coerceAtLeast(0L)
        } else 0L
        check(root.usableSpace > reserve + 25_000_000L) {
            "Not enough free storage for ${asset.relativePath} (need approximately 1 GB for GigaAM FP32)"
        }

        // Resume .part only when the server confirms exactly the requested Range.
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            val offset = if (part.isFile) part.length() else 0L
            val connection = (URL(asset.downloadUrl).openConnection() as HttpsURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "404BNF-Android-M1/1.0")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                val code = connection.responseCode
                if (code == 416 && offset > 0L && attempt == 0) {
                    check(part.delete()) { "Cannot reset invalid partial download" }
                    continue
                }
                check(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                    "HTTP $code downloading ${asset.fileName}"
                }
                val append = offset > 0L && code == HttpURLConnection.HTTP_PARTIAL
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    val expectedPrefix = "bytes $offset-"
                    check(offset > 0L && connection.getHeaderField("Content-Range")?.startsWith(expectedPrefix) == true) {
                        "Unexpected Content-Range for ${asset.fileName}"
                    }
                }
                val start = if (append) offset else 0L
                val contentBytes = connection.contentLengthLong
                val total = if (contentBytes >= 0) start + contentBytes else null
                check(total == null || total <= asset.maxBytes) { "Server response exceeds maximum model size" }
                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var count = start
                        var lastReported = -1L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            count += n
                            check(count <= asset.maxBytes) { "Model exceeds size limit: ${asset.fileName}" }
                            output.write(buffer, 0, n)
                            if (count - lastReported >= 8 * 1024 * 1024 || lastReported < 0L) {
                                onEvent(ModelEvent.Downloading(asset, count, total))
                                lastReported = count
                            }
                        }
                        output.fd.sync()
                        onEvent(ModelEvent.Downloading(asset, count, total))
                    }
                }
                check(validationError(part, asset) == null) {
                    "Downloaded ${asset.fileName} has invalid size or SHA-256 (kept as .part for diagnosis)"
                }
                check(part.renameTo(target)) { "Cannot publish verified model ${asset.fileName}" }
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Could not resume download of ${asset.fileName}")
    }
}
