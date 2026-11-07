"""A5: real GigaAM v3 E2E RNN-T via onnx-asr, conforming to A2 AsrEngine.

Frozen parity with 404BNF Pipeline v2:
* identical quiet-point chunking (16..22 s; 40 ms frame energy);
* PCM_16 WAV fed to onnx-asr.with_timestamps().recognize(path);
* same conversion of token emission times into approximate word intervals.

NOT a forced aligner. No reference transcription or diarization used.
"""
from __future__ import annotations

import math
import os
import re
import tempfile
import time
from pathlib import Path
from typing import Any, Iterator

import numpy as np

from ..contracts import AsrResult, AudioInput, EngineCapabilities, RecognizedWord
from ..model_assets import GIGAAM_MODELS_DIR, GIGAAM_MANIFEST_NAME, GIGAAM_FILES


def split_audio_by_quiet_points(waveform: np.ndarray, sr: int = 16000,
                                longest: float = 22.0) -> Iterator[tuple[float, np.ndarray]]:
    """The exact segmentation logic of the v2 run_asr.py benchmark."""
    if sr != 16000 or not (math.isfinite(longest) and longest == 22.0):
        raise ValueError('A5 parity mode requires 16 kHz and longest=22 seconds')
    cursor = 0
    max_len = round(longest * sr)
    window = max(round(0.04 * sr), 1)
    while cursor < len(waveform):
        remaining = len(waveform) - cursor
        if remaining <= max_len:
            yield cursor / sr, waveform[cursor:]
            break
        lo = cursor + round(16.0 * sr)
        hi = min(cursor + max_len, len(waveform))
        best_score, best = (float('inf'), float('inf')), hi
        target = cursor + round(20.0 * sr)
        for i in range(lo, max(lo + 1, hi - window + 1), window):
            portion = waveform[i:i + window]
            rms = float(np.mean(portion * portion))
            score = (rms, abs(i - target))
            if score < best_score:
                best_score, best = score, i
        if best <= cursor:
            raise RuntimeError('Invalid quiet-point chunk boundary')
        yield cursor / sr, waveform[cursor:best]
        cursor = best


def extract_words_v2(timestamped_result: Any, offset: float, duration: float) -> list[RecognizedWord]:
    """Frozen v2 token->word conversion (token timestamps are only estimates).

    Raises if the ONNX decoder did not return a faithful token-level alignment.
    Do not fabricate timestamps for unsupported models.
    """
    text = getattr(timestamped_result, 'text', None)
    tokens = getattr(timestamped_result, 'tokens', None)
    times = getattr(timestamped_result, 'timestamps', None)
    if not isinstance(text, str) or not isinstance(tokens, list) or not isinstance(times, list):
        raise ValueError('onnx-asr.with_timestamps() must return text, tokens and timestamps lists')
    if len(tokens) != len(times):
        raise ValueError('Token/timestamp count mismatch')
    if not tokens:
        if text.strip():
            raise ValueError('ASR returned nonempty text without timestamped tokens')
        return []
    if not all(isinstance(token, str) for token in tokens):
        raise ValueError('All ONNX tokens must be strings')

    clean = [token.replace('\u2581', ' ') for token in tokens]
    try:
        seconds = [float(t) for t in times]
    except (TypeError, ValueError) as exc:
        raise ValueError('Invalid ONNX timestamp') from exc
    if not all(math.isfinite(s) and -0.01 <= s <= duration + 1.0 for s in seconds):
        raise ValueError(f'Token timestamp outside audio chunk of {duration:.3f}s')

    chars: list[str] = []
    char_times: list[float] = []
    for token, second in zip(clean, seconds):
        chars.extend(token)
        char_times.extend([min(duration, max(0.0, second))] * len(token))
    raw = ''.join(chars)
    pieces: list[str] = []
    piece_times: list[float] = []
    cur = 0
    for match in re.finditer(r'\A\s|\s\B|(\s)\b', raw):
        pieces.extend(raw[cur:match.start()])
        piece_times.extend(char_times[cur:match.start()])
        if match.group(1):
            pieces.append(' ')
            piece_times.append(char_times[match.start()])
        cur = match.end()
    pieces.extend(raw[cur:])
    piece_times.extend(char_times[cur:])
    joined = ''.join(pieces)
    if joined != text:
        raise ValueError('ASR text disagrees with decoded tokens; refusing invented word timings')
    char_times = piece_times
    words: list[RecognizedWord] = []
    for match in re.finditer(r'\S+', joined):
        first = match.start()
        last = match.end() - 1
        start = min(duration, max(0.0, char_times[first]))
        last_token_time = max(start, char_times[last])
        next_word_match = re.search(r'\S', joined[match.end():])
        next_start = (char_times[match.end() + next_word_match.start()]
                      if next_word_match else duration)
        end = max(start, min(duration, last_token_time + 0.12,
                             max(start, next_start)))
        words.append(RecognizedWord(text=match.group(),
                                    start=round(offset + start, 3),
                                    end=round(offset + end, 3),
                                    timing_source='onnx_rnnt_token_estimate'))
    return words


class GigaAmAsrEngine:
    """CPU GigaAM live adapter; loads model once and reuses it for recordings.

    A2 canonical AudioInput is required. For parity with the prior benchmark,
    ASR chunks are written as temporary PCM16 mono WAV (not directly inferred
    from float arrays). There is no reference-data access or caching here.
    """

    model_id = 'gigaam-v3-e2e-rnnt'
    capabilities = EngineCapabilities(word_timestamps=True,
                                      overlapping_speakers=False,
                                      streaming=False, needs_network=False)

    def __init__(self, *, model: Any = None):
        self.load_seconds: float = 0.0
        if model is None:
            try:
                import onnx_asr
            except ImportError as exc:
                raise RuntimeError('onnx-asr missing: install "onnx-asr[cpu,hub]"') from exc
            started = time.perf_counter()
            # All weights are prepared by setup.ps1 into a project-local directory.
            # Refuse implicit HF downloads while processing a user's recording.
            manifest = GIGAAM_MODELS_DIR / GIGAAM_MANIFEST_NAME
            if (not manifest.is_file() or
                    any(not (GIGAAM_MODELS_DIR / filename).is_file() for filename in GIGAAM_FILES)):
                raise FileNotFoundError(
                    f'GigaAM is not installed at {GIGAAM_MODELS_DIR}; run setup.ps1')
            os.environ.setdefault('HF_HUB_OFFLINE', '1')
            base = onnx_asr.load_model(self.model_id, str(GIGAAM_MODELS_DIR))
            if not hasattr(base, 'with_timestamps'):
                raise RuntimeError('Installed onnx-asr lacks .with_timestamps(); update onnx-asr')
            model = base.with_timestamps()
            self.load_seconds = time.perf_counter() - started
        if not callable(getattr(model, 'recognize', None)):
            raise TypeError('GigaAM model must expose recognize(WAV_path)')
        self.model = model

    def recognize(self, audio: AudioInput) -> AsrResult:
        if not isinstance(audio, AudioInput):
            raise TypeError('GigaAM expects canonical A2 AudioInput (float32 mono 16kHz)')
        try:
            import soundfile as sf
        except ImportError as exc:
            raise RuntimeError('soundfile is required to write parity PCM16 chunks') from exc
        started = time.perf_counter()
        texts: list[str] = []
        words: list[RecognizedWord] = []
        chunk_metadata: list[dict[str, float | int]] = []
        with tempfile.TemporaryDirectory(prefix='404bnf_a5_asr_') as td:
            tmp = Path(td)
            for index, (offset, audio_chunk) in enumerate(
                    split_audio_by_quiet_points(audio.samples, audio.sample_rate)):
                chunk_path = tmp / f'chunk_{index:03d}.wav'
                sf.write(str(chunk_path), audio_chunk, audio.sample_rate, subtype='PCM_16')
                result = self.model.recognize(str(chunk_path))
                if not isinstance(getattr(result, 'text', None), str):
                    raise TypeError('Expected TimestampedResult: model must use .with_timestamps()')
                duration = len(audio_chunk) / audio.sample_rate
                extracted = extract_words_v2(result, offset, duration)
                words.extend(extracted)
                texts.append(result.text.strip())
                chunk_metadata.append({'start': round(offset, 3),
                                       'end': round(offset + duration, 3),
                                       'word_count': len(extracted)})
        elapsed = time.perf_counter() - started
        # Keep exact v2 semantics: concatenated original ASR text, NOT the
        # reconstructed words. Check this independently using a fresh benchmark.
        text = ' '.join(t for t in texts if t)
        return AsrResult(model_id=self.model_id, text=text, words=tuple(words),
                         timing_precision='approx_word_from_rnnt_subwords',
                         metadata={
                             'asr_chunking': 'quiet_points_max_22_seconds',
                             'chunks': chunk_metadata,
                             'inference_seconds': round(elapsed, 3),
                             'rtf': round(elapsed / audio.duration, 4),
                             'load_seconds': round(self.load_seconds, 3),
                             'benchmark_note': ('ASR includes temporary PCM16 chunk writing and ONNX inference; '
                                                'excludes model load and A3 decoding'),
                         })
