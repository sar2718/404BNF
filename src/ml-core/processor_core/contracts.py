"""Model-agnostic internal types for 404BNF. All times are seconds."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import numpy as np


@dataclass(frozen=True)
class EngineCapabilities:
    word_timestamps: bool = False
    overlapping_speakers: bool = False
    streaming: bool = False
    needs_network: bool = False


@dataclass(frozen=True)
class AudioInput:
    """Canonical audio after preprocessing: contiguous float32 mono / 16 kHz.

    original_sha256 is computed on the original file's actual bytes. The filename
    is a local reference only: callers should replace it with an Android URI.
    """
    record_id: str
    samples: np.ndarray
    sample_rate: int
    original_path: Path
    original_sha256: str

    def __post_init__(self) -> None:
        if not self.record_id or self.record_id in {'.', '..'}:
            raise ValueError('record_id is required')
        if self.sample_rate != 16000:
            raise ValueError('AudioInput requires 16 kHz; resample in AudioPreprocessor')
        if (not isinstance(self.samples, np.ndarray) or self.samples.ndim != 1
                or self.samples.dtype != np.float32 or not self.samples.flags.c_contiguous):
            raise ValueError('AudioInput requires contiguous mono numpy.float32 samples')
        if not self.samples.size or not np.all(np.isfinite(self.samples)):
            raise ValueError('AudioInput samples must be nonempty and finite')
        if float(np.max(np.abs(self.samples))) > 1.001:
            raise ValueError('AudioInput samples exceed [-1, 1]')
        if len(self.original_sha256) != 64 or any(c not in '0123456789abcdef' for c in self.original_sha256):
            raise ValueError('original_sha256 must be lowercase SHA-256 hex')

    @property
    def duration(self) -> float:
        return len(self.samples) / self.sample_rate


@dataclass(frozen=True)
class RecognizedWord:
    text: str
    start: float | None
    end: float | None
    timing_source: str | None = None


@dataclass(frozen=True)
class AsrResult:
    model_id: str
    text: str
    words: tuple[RecognizedWord, ...]
    timing_precision: str = 'unspecified'
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class SpeakerTurn:
    start: float
    end: float
    speaker_id: str


@dataclass(frozen=True)
class DiarizationResult:
    model_id: str
    turns: tuple[SpeakerTurn, ...]
    metadata: dict[str, Any] = field(default_factory=dict)


class AsrEngine(Protocol):
    model_id: str
    capabilities: EngineCapabilities

    def recognize(self, audio: AudioInput) -> AsrResult: ...


class DiarizationEngine(Protocol):
    model_id: str
    capabilities: EngineCapabilities

    def diarize(self, audio: AudioInput) -> DiarizationResult: ...


class ProcessingBackend(Protocol):
    """Identical output contract for local model execution and a future API."""
    def process(self, audio: AudioInput) -> dict[str, Any]: ...
