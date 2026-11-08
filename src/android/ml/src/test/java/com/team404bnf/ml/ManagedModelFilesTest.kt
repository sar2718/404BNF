package com.team404bnf.ml

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ManagedModelFilesTest {
    @Test fun `remove selected component and partial files without touching any user data or other models`() {
        val temp = Files.createTempDirectory("404bnf-model-removal-").toFile()
        try {
            val root = File(temp, "models").apply { mkdirs() }
            val files = ModelCatalog.assets.associateWith { asset ->
                File(root, asset.relativePath).apply { parentFile!!.mkdirs(); writeText(asset.fileName) }
            }
            val vad = files.entries.single { it.key.model == ModelId.SILERO_VAD }.value
            val part = File(vad.path + ".part").apply { writeText("partial") }
            val extra = File(root, "unmanaged.txt").apply { writeText("preserve") }
            val audio = File(temp, "recordings/record/audio.wav").apply { parentFile!!.mkdirs(); writeText("audio") }
            val edits = File(temp, "recordings/record/edits.json").apply { writeText("edits") }
            val builtIn = File(temp, "assets/model.onnx").apply { parentFile!!.mkdirs(); writeText("asset") }
            removeManagedModelFiles(root, setOf(ModelId.SILERO_VAD))
            assertFalse(vad.exists())
            assertFalse(part.exists())
            files.filterKeys { it.model != ModelId.SILERO_VAD }.values.forEach { assertTrue(it.isFile) }
            assertEquals("audio", audio.readText())
            assertEquals("edits", edits.readText())
            assertEquals("asset", builtIn.readText())
            assertEquals("preserve", extra.readText())
        } finally { temp.deleteRecursively() }
    }

    @Test fun `symlink to another model cannot expand deletion scope`() {
        val temp = Files.createTempDirectory("404bnf-model-symlink-").toFile()
        try {
            val root = File(temp, "models").apply { mkdirs() }
            val vadAsset = ModelCatalog.filesFor(ModelId.SILERO_VAD).single()
            val vad = File(root, vadAsset.relativePath).apply { parentFile!!.mkdirs() }
            val other = File(root, "other.onnx").apply { writeText("keep") }
            Files.createSymbolicLink(vad.toPath(), other.toPath())
            try {
                removeManagedModelFiles(root, setOf(ModelId.SILERO_VAD))
                fail("A model symlink must be rejected")
            } catch (_: IllegalStateException) {
                assertEquals("keep", other.readText())
            }
        } finally { temp.deleteRecursively() }
    }

    @Test fun `catalog path that became a directory is not recursively removed`() {
        val temp = Files.createTempDirectory("404bnf-model-directory-").toFile()
        try {
            val root = File(temp, "models").apply { mkdirs() }
            val target = File(root, ModelCatalog.filesFor(ModelId.SILERO_VAD).single().relativePath).apply { mkdirs() }
            val data = File(target, "unexpected-user-file").apply { writeText("keep") }
            try {
                removeManagedModelFiles(root, setOf(ModelId.SILERO_VAD))
                fail("Must not delete a directory recursively")
            } catch (_: IllegalStateException) {
                assertEquals("keep", data.readText())
            }
        } finally { temp.deleteRecursively() }
    }
}
