#!/usr/bin/env python3
"""One-time pinned ONNX model installer and on-disk integrity checker.

Does NOT download INT8, use credentials, or contact a server during inference.
The installer needs network once. The regular run.py does not.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from processor_core.model_assets import (
    GIGAAM_MODEL_ID, GIGAAM_REVISION, GIGAAM_REPO,
    GIGAAM_FILES, GIGAAM_ONNX_SHA256, GIGAAM_MODELS_DIR, GIGAAM_MANIFEST_NAME,
    MOBILE_MODELS, MOBILE_MODELS_DIR,
)

# FP32 encoder is approximately 885 MB; an INT8 encoder is roughly 225 MB.
MIN_FP32_ENCODER_BYTES = 500_000_000
MANIFEST_VERSION = 1


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as src:
        for block in iter(lambda: src.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def download_one(path: Path, url: str, expected: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file():
        if sha256(path) != expected:
            raise RuntimeError(f'{path}: wrong SHA-256; existing file was NOT overwritten')
        print(f'[OK] Already present, SHA256 verified: {path.name}', flush=True)
        return
    partial = path.with_name(path.name + '.partial')
    partial.unlink(missing_ok=True)
    try:
        req = urllib.request.Request(url, headers={'User-Agent': '404BNF-ML-Core-Setup/1.0'})
        with urllib.request.urlopen(req, timeout=90) as response, partial.open('wb') as out:
            while block := response.read(1024 * 1024):
                out.write(block)
        found = sha256(partial)
        if found != expected:
            raise RuntimeError(f'{path.name}: SHA-256 mismatch ({found}); refusing model')
        os.replace(partial, path)
        print(f'[OK] Downloaded and verified: {path.name}', flush=True)
    finally:
        partial.unlink(missing_ok=True)


def mobile(install: bool) -> None:
    for name, (url, digest) in MOBILE_MODELS.items():
        target = MOBILE_MODELS_DIR / name
        if install:
            download_one(target, url, digest)
        else:
            if not target.is_file() or sha256(target) != digest:
                raise RuntimeError(f'Missing/corrupted {target}; rerun setup.ps1')
            print(f'[OK] SHA256: {name}', flush=True)


def paths_and_hashes():
    result = {}
    for name in GIGAAM_FILES:
        file = GIGAAM_MODELS_DIR / name
        if not file.is_file() or file.stat().st_size == 0:
            raise RuntimeError(f'Missing GigaAM FP32 file: {file}')
        result[name] = {'size': file.stat().st_size, 'sha256': sha256(file)}
    if (GIGAAM_MODELS_DIR / 'v3_e2e_rnnt_encoder.onnx').stat().st_size < MIN_FP32_ENCODER_BYTES:
        raise RuntimeError('GigaAM encoder too small; expected original FP32 model, not INT8')
    for name, expected in GIGAAM_ONNX_SHA256.items():
        if result[name]['sha256'] != expected:
            raise RuntimeError(f'GigaAM FP32 SHA-256 mismatch for {name}; refusing model')
    return result


def manifest_dict(hashes):
    return {
        'schema_version': MANIFEST_VERSION,
        'model_id': GIGAAM_MODEL_ID,
        'repo': GIGAAM_REPO,
        'revision': GIGAAM_REVISION,
        'precision': 'fp32',
        'files': hashes,
    }


def verify_gigaam() -> None:
    mpath = GIGAAM_MODELS_DIR / GIGAAM_MANIFEST_NAME
    if not mpath.is_file():
        raise RuntimeError(f'GigaAM manifest not found: {mpath}; rerun setup.ps1')
    try:
        manifest = json.loads(mpath.read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError('GigaAM manifest unreadable; rerun setup.ps1') from exc
    if manifest != manifest_dict(paths_and_hashes()):
        raise RuntimeError('GigaAM model hashes or revision differ from installation manifest')
    print(f'[OK] GigaAM original FP32 files verified ({GIGAAM_REVISION[:7]})', flush=True)


def install_gigaam() -> None:
    marker = GIGAAM_MODELS_DIR / GIGAAM_MANIFEST_NAME
    if marker.is_file():
        verify_gigaam()
        return
    try:
        from huggingface_hub import hf_hub_download
    except ImportError as exc:
        raise RuntimeError('Missing huggingface-hub; install requirements-win-py313.lock first') from exc

    # onnx-asr==0.12.0 has no `revision` argument on load_model().
    # Pin the revision at download time using huggingface_hub instead.
    print('[...] Preparing GigaAM FP32 in models/gigaam-v3-e2e-rnnt', flush=True)
    GIGAAM_MODELS_DIR.mkdir(parents=True, exist_ok=True)
    for filename in GIGAAM_FILES:
        target = GIGAAM_MODELS_DIR / filename
        expected = GIGAAM_ONNX_SHA256.get(filename)
        # Never silently overwrite an existing but untrusted ONNX payload.
        if target.exists():
            if not target.is_file():
                raise RuntimeError(f'GigaAM model path is not a file: {target}')
            if expected is not None and sha256(target) != expected:
                raise RuntimeError(f'Wrong SHA-256 for existing {target}; NOT overwritten')
        # The Hub downloads exactly this file from the pinned Git commit,
        # stores it in the project's models directory and can resume failures.
        # No `onnx_asr.load_model` call is made while downloading.
        result_path = Path(hf_hub_download(
            repo_id=GIGAAM_REPO,
            filename=filename,
            revision=GIGAAM_REVISION,
            local_dir=str(GIGAAM_MODELS_DIR),
        ))
        if result_path.resolve() != target.resolve():
            raise RuntimeError(f'HF Hub returned unexpected file location for {filename}: {result_path}')
        if not target.is_file() or not target.stat().st_size:
            raise RuntimeError(f'GigaAM file missing after download: {target}')
        if expected is not None and sha256(target) != expected:
            raise RuntimeError(f'SHA-256 mismatch for downloaded {filename}; refusing model')
        print(f'[OK] GigaAM pinned file: {filename}', flush=True)

    hashes = paths_and_hashes()
    payload = manifest_dict(hashes)
    temp = marker.with_name(marker.name + '.partial')
    try:
        temp.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        os.replace(temp, marker)
    finally:
        temp.unlink(missing_ok=True)
    print('[OK] GigaAM FP32 installed locally; pinned hashes recorded', flush=True)


def offline_load() -> None:
    # Force Hugging Face into offline mode for the validation attempt.
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    import onnx_asr
    model = onnx_asr.load_model(GIGAAM_MODEL_ID, str(GIGAAM_MODELS_DIR))
    if not hasattr(model, 'with_timestamps'):
        raise RuntimeError('GigaAM timestamps API unavailable')
    model.with_timestamps()
    print('[OK] GigaAM loads locally with HF_HUB_OFFLINE=1', flush=True)


def cli(argv=None):
    parser = argparse.ArgumentParser(description='404BNF pinned models: install or verify')
    parser.add_argument('command', choices=['install', 'verify'])
    parser.add_argument('--check-inference', action='store_true',
                        help='Also instantiate ONNX GigaAM model offline (RAM intensive)')
    args = parser.parse_args(argv)
    if args.command == 'install':
        mobile(True)
        install_gigaam()
    mobile(False)
    verify_gigaam()
    if args.check_inference:
        offline_load()
    print('[READY] Models installed and integrity-checked.', flush=True)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(cli())
    except Exception as exc:
        print(f'[ERROR] {exc}', file=sys.stderr, flush=True)
        raise SystemExit(1)
