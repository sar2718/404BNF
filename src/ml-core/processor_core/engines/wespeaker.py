"""Silero VAD + WeSpeaker CAM++ ONNX -> DiarizationResult.

Uses the algorithms and ONNX wrappers in diarization_impl.py.
Its input is canonical AudioInput; it NEVER reads a WAV itself.
No manifest, ASR reference or ground-truth speaker counts are read.
"""
from __future__ import annotations

import math
import time
from pathlib import Path
from typing import Any

import numpy as np

from ..contracts import AudioInput, DiarizationResult, EngineCapabilities, SpeakerTurn


class WeSpeakerDiarizationEngine:
    model_id = 'silero-wespeaker-campp'
    capabilities = EngineCapabilities(word_timestamps=False,
                                       overlapping_speakers=False,
                                       streaming=False, needs_network=False)

    def __init__(self, *, models_dir: str | Path = 'models/mobile_diarization',
                 vad_threshold: float = 0.5, cluster_distance: float = 0.55,
                 window: float = 2.0, hop: float = 0.8,
                 num_speakers: int | None = None, threads: int = 2,
                 # Injection points for pure unit tests. Ordinary callers omit these.
                 vad: Any = None, embedder: Any = None, legacy_module: Any = None):
        if not (math.isfinite(vad_threshold) and 0 < vad_threshold < 1):
            raise ValueError('vad_threshold must be in (0,1)')
        if not (math.isfinite(cluster_distance) and 0 < cluster_distance < 2):
            raise ValueError('cluster_distance must be in (0,2)')
        if not (math.isfinite(window) and .5 <= window <= 10):
            raise ValueError('window must be between 0.5 and 10 s')
        if not (math.isfinite(hop) and 0 < hop <= window):
            raise ValueError('hop must be >0 and <= window')
        if not 1 <= threads <= 16:
            raise ValueError('threads must be between 1 and 16')
        if num_speakers is not None and (isinstance(num_speakers, bool)
                                         or not isinstance(num_speakers, int)
                                         or not 1 <= num_speakers <= 20):
            raise ValueError('num_speakers must be between 1 and 20')
        if (vad is None) != (embedder is None):
            raise ValueError('Provide BOTH vad and embedder for test injection')
        self.vad_threshold = float(vad_threshold)
        self.cluster_distance = float(cluster_distance)
        self.window = float(window)
        self.hop = float(hop)
        self.num_speakers = num_speakers
        self.threads = threads
        self.models_dir = Path(models_dir)
        from . import diarization_impl
        self.legacy = legacy_module or diarization_impl
        if vad is None:
            vad_path = self.models_dir / 'silero_vad_v6.2.1.onnx'
            emb_path = self.models_dir / 'voxceleb_CAM++.onnx'
            for path in (vad_path, emb_path):
                if not path.is_file():
                    raise FileNotFoundError(f'Missing ONNX model {path}; copy ONNX weights into models/mobile_diarization')
            # Real weights and ONNX Runtime are loaded ONCE per adapter instance.
            self.vad = self.legacy.SileroOnnx(vad_path, threads=threads)
            self.embedder = self.legacy.WespeakerOnnx(emb_path, threads=threads)
        else:
            self.vad = vad
            self.embedder = embedder

    def diarize(self, audio: AudioInput) -> DiarizationResult:
        if not isinstance(audio, AudioInput):
            raise TypeError('Expected the canonical AudioInput')
        if audio.sample_rate != self.legacy.SAMPLE_RATE:
            raise ValueError('Expected 16kHz AudioInput')
        started = time.perf_counter()
        # Silero.probabilities() resets its internal state at EACH call.
        probs = self.vad.probabilities(audio.samples)
        speech = self.legacy.speech_intervals(probs, audio.duration,
                                              threshold=self.vad_threshold)
        windows = self.legacy.make_windows(speech, size=self.window, hop=self.hop)
        embeddings = []
        for w in windows:
            a = max(0, int(w['start'] * audio.sample_rate))
            b = min(len(audio.samples), int(w['end'] * audio.sample_rate))
            try:
                embeddings.append(self.embedder.embed(
                    np.ascontiguousarray(audio.samples[a:b])))
            except ValueError as exc:
                raise RuntimeError(
                    f'CAM++ failed for {audio.record_id} at '
                    f'{w["start"]:.3f}–{w["end"]:.3f} s: {exc}') from exc
        labels = self.legacy.cluster_embeddings(
            embeddings, distance=self.cluster_distance,
            num_speakers=self.num_speakers)
        raw_turns = self.legacy.assign_timeline(speech, windows, labels,
                                                audio.duration)
        # Preserve UNKNOWN intervals as metadata; SpeakerTurn explicitly
        # forbids a synthetic UNKNOWN voice. The pipeline may attribute nearby
        # words by its temporal fallback, which can differ on short turns.
        unknown_turns = [{'start': float(row['start']), 'end': float(row['end'])}
                         for row in raw_turns if row['speaker_id'] == 'UNKNOWN']
        turns = tuple(SpeakerTurn(start=float(row['start']), end=float(row['end']),
                                  speaker_id=str(row['speaker_id']))
                      for row in raw_turns if row['speaker_id'] != 'UNKNOWN')
        elapsed = time.perf_counter() - started
        metadata: dict[str, Any] = {
            'engine': 'silero_vad_onnx+wespeaker_campplus_onnx+average_linkage',
            'speaker_count_mode': 'user_fixed' if self.num_speakers is not None else 'auto',
            'vad_threshold': self.vad_threshold,
            'cluster_distance': self.cluster_distance,
            'embedding_windows': len(windows),
            'vad_intervals': len(speech),
            'vad_speech_seconds': round(sum(b-a for a, b in speech), 3),
            'speakers_detected': len({turn.speaker_id for turn in turns}),
            'unknown_turns': unknown_turns,
            'inference_seconds': round(elapsed, 3),
            'rtf': round(elapsed / audio.duration, 4),
            'benchmark_note': ('Diarization only: VAD+FBank+CAM++ + clustering; '
                               'excludes audio preprocessing and model loading'),
            'capability_note': 'Single-speaker timeline; simultaneous speech is not separated',
        }
        return DiarizationResult(model_id=self.model_id, turns=turns,
                                 metadata=metadata)
