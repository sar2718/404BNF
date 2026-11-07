"""Pinned model assets for the Windows reference ML core (not Android)."""
from __future__ import annotations

from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
MOBILE_MODELS_DIR = PROJECT_ROOT / 'models' / 'mobile_diarization'
GIGAAM_MODEL_ID = 'gigaam-v3-e2e-rnnt'
GIGAAM_REPO = 'istupakov/gigaam-v3-onnx'
# Commit that contains the upstream FP32 GigaAM v3 E2E RNNT artifact set.
GIGAAM_REVISION = '322c3b29492673eb7d0b434bfa9dfb8653e34d02'
GIGAAM_MODELS_DIR = PROJECT_ROOT / 'models' / GIGAAM_MODEL_ID
GIGAAM_MANIFEST_NAME = '.404bnf-model-manifest.json'
# Must never substitute quantized (.int8.onnx) weights.
GIGAAM_FILES = (
    'config.json',
    'v3_e2e_rnnt.yaml',
    'v3_e2e_rnnt_vocab.txt',
    'v3_e2e_rnnt_encoder.onnx',
    'v3_e2e_rnnt_decoder.onnx',
    'v3_e2e_rnnt_joint.onnx',
)
# SHA-256 of upstream full-precision ONNX payloads (Git LFS at the pinned revision).
# Never download or use the *.int8.onnx alternatives.
GIGAAM_ONNX_SHA256 = {
    'v3_e2e_rnnt_encoder.onnx': 'cd60b3764a832e8560ae6d3ad0b10adc1a42ffae412b9476f25620aae4f4a508',
    'v3_e2e_rnnt_decoder.onnx': '7b0a16d67fd2cb37061decc93c69e364a9ab27afee3c57495d55b1c974cf7231',
    'v3_e2e_rnnt_joint.onnx': '602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c',
}
MOBILE_MODELS = {
    'silero_vad_v6.2.1.onnx': (
        'https://huggingface.co/bitsydarel/silero-vad-onnx/resolve/main/silero_vad_v6.2.1.onnx',
        '1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3',
    ),
    'voxceleb_CAM++.onnx': (
        'https://huggingface.co/Wespeaker/wespeaker-voxceleb-campplus/resolve/main/voxceleb_CAM%2B%2B.onnx',
        'b50810498b5bcf5773d086f6993d344476bd0c88b566a41e8d801aaf8461efad',
    ),
}
