"""A2 loader: mono 16-kHz PCM16 WAV only; no hidden resampling.

For M4A/MP3 add an explicit AudioPreprocessor in A3, preserving source hash.
"""
from __future__ import annotations

import hashlib
import wave
from pathlib import Path

import numpy as np

from .contracts import AudioInput


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def load_pcm16_wav(path: str | Path, record_id: str) -> AudioInput:
    file = Path(path).expanduser().resolve(strict=True)
    with wave.open(str(file), 'rb') as audio:
        if audio.getnchannels() != 1 or audio.getframerate() != 16000:
            raise ValueError('A2 accepts only mono, 16000 Hz WAV; preprocess audio first')
        if audio.getsampwidth() != 2 or audio.getcomptype() != 'NONE':
            raise ValueError('A2 accepts only uncompressed 16-bit PCM WAV')
        raw = audio.readframes(audio.getnframes())
    samples = np.frombuffer(raw, dtype='<i2').astype(np.float32) / 32768.0
    return AudioInput(record_id=record_id, samples=np.ascontiguousarray(samples),
                      sample_rate=16000, original_path=file,
                      original_sha256=sha256_file(file))
