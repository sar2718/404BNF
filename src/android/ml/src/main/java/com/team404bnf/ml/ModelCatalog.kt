package com.team404bnf.ml

/**
 * Same *original FP32* artifacts as src/ml-core/processor_core/model_assets.py.
 * The remote repository revision for GigaAM is pinned, never use *.int8.onnx.
 * ONNX hashes are pinned to the Python reference; small config files come from
 * the pinned immutable Git commit over HTTPS.
 */
enum class ModelId { GIGAAM_FP32, SILERO_VAD, WESPEAKER_CAMPP }

data class ModelAsset(
    val model: ModelId,
    val relativePath: String,
    val downloadUrl: String,
    val expectedSha256: String?,
    val maxBytes: Long,
    val minBytes: Long = 1L,
) {
    init {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") &&
                relativePath.split('/').none { it == ".." || it.isEmpty() })
        require(downloadUrl.startsWith("https://"))
        require(maxBytes >= minBytes && minBytes > 0L)
        require(expectedSha256 == null || expectedSha256.matches(Regex("[0-9a-f]{64}")))
    }
    val fileName: String get() = relativePath.substringAfterLast('/')
}

object ModelCatalog {
    const val GIGAAM_REVISION = "322c3b29492673eb7d0b434bfa9dfb8653e34d02"
    private const val HF = "https://huggingface.co"
    private const val GIGAAM_BASE = "$HF/istupakov/gigaam-v3-onnx/resolve/$GIGAAM_REVISION"
    private const val GIGAAM_DIR = "gigaam-v3-e2e-rnnt"
    private const val MOBILE_DIR = "mobile_diarization"

    val assets: List<ModelAsset> = listOf(
        ModelAsset(ModelId.GIGAAM_FP32, "$GIGAAM_DIR/config.json",
            "$GIGAAM_BASE/config.json", null, 2_000_000),
        ModelAsset(ModelId.GIGAAM_FP32, "$GIGAAM_DIR/v3_e2e_rnnt.yaml",
            "$GIGAAM_BASE/v3_e2e_rnnt.yaml", null, 2_000_000),
        ModelAsset(ModelId.GIGAAM_FP32, "$GIGAAM_DIR/v3_e2e_rnnt_vocab.txt",
            "$GIGAAM_BASE/v3_e2e_rnnt_vocab.txt", null, 10_000_000),
        ModelAsset(ModelId.GIGAAM_FP32, "$GIGAAM_DIR/v3_e2e_rnnt_encoder.onnx",
            "$GIGAAM_BASE/v3_e2e_rnnt_encoder.onnx",
            "cd60b3764a832e8560ae6d3ad0b10adc1a42ffae412b9476f25620aae4f4a508",
            1_200_000_000L, 500_000_000L),
        ModelAsset(ModelId.GIGAAM_FP32, "$GIGAAM_DIR/v3_e2e_rnnt_decoder.onnx",
            "$GIGAAM_BASE/v3_e2e_rnnt_decoder.onnx",
            "7b0a16d67fd2cb37061decc93c69e364a9ab27afee3c57495d55b1c974cf7231",
            150_000_000L),
        ModelAsset(ModelId.GIGAAM_FP32, "$GIGAAM_DIR/v3_e2e_rnnt_joint.onnx",
            "$GIGAAM_BASE/v3_e2e_rnnt_joint.onnx",
            "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c",
            150_000_000L),
        ModelAsset(ModelId.SILERO_VAD, "$MOBILE_DIR/silero_vad_v6.2.1.onnx",
            "$HF/bitsydarel/silero-vad-onnx/resolve/main/silero_vad_v6.2.1.onnx",
            "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
            20_000_000L),
        ModelAsset(ModelId.WESPEAKER_CAMPP, "$MOBILE_DIR/voxceleb_CAM++.onnx",
            "$HF/Wespeaker/wespeaker-voxceleb-campplus/resolve/main/voxceleb_CAM%2B%2B.onnx",
            "b50810498b5bcf5773d086f6993d344476bd0c88b566a41e8d801aaf8461efad",
            100_000_000L),
    )

    val allModels = listOf(ModelId.SILERO_VAD, ModelId.WESPEAKER_CAMPP, ModelId.GIGAAM_FP32)

    fun filesFor(model: ModelId): List<ModelAsset> = assets.filter { it.model == model }

    fun onnxFilesFor(model: ModelId): List<ModelAsset> = filesFor(model).filter { it.fileName.endsWith(".onnx") }
}
