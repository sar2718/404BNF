package com.team404bnf.ml

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun `all models are defined`() {
        assertEquals(8, ModelCatalog.assets.size)
        assertEquals(3, ModelCatalog.allModels.size)
        assertEquals(3, ModelCatalog.onnxFilesFor(ModelId.GIGAAM_FP32).size)
        assertEquals(1, ModelCatalog.onnxFilesFor(ModelId.SILERO_VAD).size)
        assertEquals(1, ModelCatalog.onnxFilesFor(ModelId.WESPEAKER_CAMPP).size)
    }

    @Test fun `pinned original FP32 and fixed SHA256`() {
        assertEquals(40, ModelCatalog.GIGAAM_REVISION.length)
        assertTrue(ModelCatalog.assets.none { it.relativePath.contains("int8", ignoreCase = true) })
        assertTrue(ModelCatalog.assets.all { it.downloadUrl.startsWith("https://") })
        assertTrue(ModelCatalog.assets.filter { it.fileName.endsWith(".onnx") }
            .all { it.expectedSha256?.length == 64 })
        assertTrue(ModelCatalog.assets.first { it.fileName.contains("encoder.onnx") }.minBytes > 500_000_000L - 1)
    }

    @Test fun `relative paths cannot escape private directory`() {
        assertTrue(ModelCatalog.assets.all { ".." !in it.relativePath && !it.relativePath.startsWith("/") })
        assertEquals(ModelCatalog.assets.size, ModelCatalog.assets.map { it.relativePath }.toSet().size)
    }
    @Test fun `offline package includes every required recognition and diarization dependency`() {
        val pack = ModelCatalog.packages.single { it.id == ModelCatalog.DEFAULT_PACKAGE_ID }
        assertEquals(ModelCatalog.allModels.toSet(), pack.models)
        assertEquals(ModelCatalog.assets.toSet(), ModelCatalog.assets.filter { it.model in pack.models }.toSet())
        assertTrue(pack.approximateDownloadBytes > 0)
        assertEquals(ModelCatalog.packages.size, ModelCatalog.packages.map { it.id }.toSet().size)
    }

}
