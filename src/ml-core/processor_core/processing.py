"""A6: managed execution of the unchanged A2 pipeline, using the A3 preprocessor.

Each model is invoked exactly once. Stage states are deliberately discrete:
no artificial percentage completion inside synchronous ONNX inference.
"""
from __future__ import annotations

import json
import logging
import math
import os
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Callable

from .contracts import (AudioInput, AsrEngine, AsrResult, DiarizationEngine,
                        DiarizationResult, EngineCapabilities)
from .pipeline import LocalAudioProcessor, PipelineConfig
from .preprocess import AudioPreprocessor

LOG = logging.getLogger(__name__)


class ProcessingStatus(str, Enum):
    CREATED = 'CREATED'
    PREPROCESSING = 'PREPROCESSING'
    ASR_PROCESSING = 'ASR_PROCESSING'
    DIARIZATION_PROCESSING = 'DIARIZATION_PROCESSING'
    MERGING = 'MERGING'
    COMPLETED = 'COMPLETED'
    FAILED = 'FAILED'
    CANCELLED = 'CANCELLED'


class ErrorCode(str, Enum):
    INVALID_AUDIO = 'INVALID_AUDIO'
    ASR_FAILED = 'ASR_FAILED'
    DIARIZATION_FAILED = 'DIARIZATION_FAILED'
    INVALID_RESULT = 'INVALID_RESULT'
    OUTPUT_EXISTS = 'OUTPUT_EXISTS'
    OUTPUT_FAILED = 'OUTPUT_FAILED'
    INTERNAL_ERROR = 'INTERNAL_ERROR'


class ProcessingCancelled(Exception):
    """Raised only at an explicit cancellation checkpoint."""


class ProcessingFailure(Exception):
    def __init__(self, code: ErrorCode, stage: ProcessingStatus, message: str):
        self.code = code
        self.stage = stage
        super().__init__(message)


class CancellationToken:
    """Thread-safe cooperative cancellation; optional external check for CLI files."""

    def __init__(self, external_check: Callable[[], bool] | None = None):
        self._cancelled = threading.Event()
        self._external_check = external_check

    def cancel(self) -> None:
        self._cancelled.set()

    @property
    def is_cancelled(self) -> bool:
        return self._cancelled.is_set() or (self._external_check is not None and bool(self._external_check()))

    def check(self) -> None:
        if self.is_cancelled:
            raise ProcessingCancelled('Обработка отменена')


@dataclass(frozen=True)
class ProcessingSnapshot:
    job_id: str
    record_id: str
    status: ProcessingStatus
    stage_number: int
    stage_total: int
    updated_at: str
    error_code: str | None = None
    error_message: str | None = None
    result_id: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            'job_id': self.job_id,
            'record_id': self.record_id,
            'status': self.status.value,
            'stage_number': self.stage_number,
            'stage_total': self.stage_total,
            'updated_at': self.updated_at,
            'error_code': self.error_code,
            'error_message': self.error_message,
            'result_id': self.result_id,
        }


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


class ProgressTracker:
    """Emits immutable stage snapshots. No percentage based on model runtime."""

    _STAGES = {
        ProcessingStatus.CREATED: 0,
        ProcessingStatus.PREPROCESSING: 1,
        ProcessingStatus.ASR_PROCESSING: 2,
        ProcessingStatus.DIARIZATION_PROCESSING: 3,
        ProcessingStatus.MERGING: 4,
        ProcessingStatus.COMPLETED: 4,
    }

    def __init__(self, record_id: str, callback: Callable[[ProcessingSnapshot], None] | None = None,
                 job_id: str | None = None):
        self._lock = threading.Lock()
        self._callback = callback
        self._snapshot = ProcessingSnapshot(
            job_id=job_id or str(uuid.uuid4()), record_id=record_id,
            status=ProcessingStatus.CREATED, stage_number=0,
            stage_total=4, updated_at=_utc_now())
        self._history = [self._snapshot]
        self._notify(self._snapshot)

    @property
    def snapshot(self) -> ProcessingSnapshot:
        with self._lock:
            return self._snapshot

    @property
    def history(self) -> tuple[ProcessingSnapshot, ...]:
        with self._lock:
            return tuple(self._history)

    def update(self, status: ProcessingStatus, *, error_code: str | None = None,
               error_message: str | None = None, result_id: str | None = None) -> ProcessingSnapshot:
        with self._lock:
            previous = self._snapshot
            if previous.status in (ProcessingStatus.COMPLETED, ProcessingStatus.FAILED,
                                   ProcessingStatus.CANCELLED):
                raise RuntimeError('Cannot change a terminal processing status')
            stage_number = self._STAGES.get(status, previous.stage_number)
            if status not in (ProcessingStatus.FAILED, ProcessingStatus.CANCELLED) \
                    and stage_number < previous.stage_number:
                raise ValueError('Processing stages must not go backwards')
            snap = ProcessingSnapshot(
                job_id=previous.job_id, record_id=previous.record_id,
                status=status, stage_number=stage_number, stage_total=4,
                updated_at=_utc_now(), error_code=error_code,
                error_message=error_message, result_id=result_id)
            self._snapshot = snap
            self._history.append(snap)
        self._notify(snap)
        return snap

    def _notify(self, snapshot: ProcessingSnapshot) -> None:
        if self._callback is not None:
            try:
                self._callback(snapshot)
            except Exception:
                # Telemetry/UI failure must not change inference or final transcript.
                LOG.debug('Processing progress callback raised an exception', exc_info=True)


def _ensure_number(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f'{name} is not a number')
    numeric = float(value)
    if not math.isfinite(numeric):
        raise ValueError(f'{name} is not finite')
    return numeric


def validate_transcript(data: dict[str, Any], audio: AudioInput) -> None:
    """Validate A2 JSON-v1 without deleting/changing fields or inventing confidence.

    This validation is intended for newly produced transcripts, not legacy input.
    """
    if not isinstance(data, dict) or data.get('schema_version') != 1:
        raise ValueError('Transcript must have schema_version=1')
    if data.get('id') != audio.record_id:
        raise ValueError('Transcript id does not match input audio')
    if data.get('audio_sha256') != audio.original_sha256:
        raise ValueError('Transcript SHA-256 does not match source audio')
    try:
        uuid.UUID(str(data['result_id']))
    except (KeyError, ValueError, TypeError, AttributeError) as exc:
        raise ValueError('Invalid result_id UUID') from exc
    duration = _ensure_number(data.get('duration'), 'duration')
    if abs(duration - audio.duration) > 0.005:
        raise ValueError('Transcript duration differs from audio duration')
    for key in ('asr_model', 'diarization'):
        if not isinstance(data.get(key), str) or not data[key]:
            raise ValueError(f'Invalid {key}')
    labels = data.get('speaker_labels')
    if not isinstance(labels, dict) or not all(
            isinstance(key, str) and isinstance(value, str)
            for key, value in labels.items()):
        raise ValueError('Invalid speaker_labels')
    if data.get('speakers_detected') != len(labels):
        raise ValueError('Incorrect speakers_detected count')
    seen_ids: set[str] = set()
    for list_name in ('words', 'segments'):
        elements = data.get(list_name)
        if not isinstance(elements, list):
            raise ValueError(f'{list_name} must be a list')
        prior_start = -1.0
        for element in elements:
            if not isinstance(element, dict):
                raise ValueError(f'Invalid {list_name} item')
            start = _ensure_number(element.get('start'), 'start')
            end = _ensure_number(element.get('end'), 'end')
            if not (0 <= start <= end <= audio.duration + 0.005):
                raise ValueError(f'{list_name}: invalid time bounds')
            if start < prior_start - 0.0001:
                raise ValueError(f'{list_name} are not chronological')
            prior_start = start
            if not isinstance(element.get('text'), str):
                raise ValueError(f'{list_name}: text must be string')
            speaker = element.get('speaker_id')
            if not isinstance(speaker, str) or (speaker != 'UNKNOWN' and speaker not in labels):
                raise ValueError(f'{list_name}: unregistered speaker_id')
            if type(element.get('overlap')) is not bool or type(element.get('speaker_ambiguous')) is not bool:
                raise ValueError(f'{list_name}: invalid overlap/ambiguous flags')
            if list_name == 'segments':
                seg_id = element.get('id')
                if not isinstance(seg_id, str) or not seg_id or seg_id in seen_ids:
                    raise ValueError('Duplicate or missing segments[].id')
                seen_ids.add(seg_id)
    words = data['words']
    if data.get('words_unassigned') != sum(w['speaker_id'] == 'UNKNOWN' for w in words):
        raise ValueError('Wrong words_unassigned count')
    if data.get('words_ambiguous') != sum(w['speaker_ambiguous'] for w in words):
        raise ValueError('Wrong words_ambiguous count')
    # Also rejects NaN/Infinity anywhere in model metadata.
    json.dumps(data, ensure_ascii=False, allow_nan=False)


class _AsrResultAdapter:
    """In-memory adapter: reuse A2 merge logic without re-running ASR."""
    def __init__(self, result: AsrResult):
        self.model_id = result.model_id
        self.capabilities = EngineCapabilities(word_timestamps=True)
        self._result = result

    def recognize(self, audio: AudioInput) -> AsrResult:
        return self._result


class _DiarResultAdapter:
    def __init__(self, result: DiarizationResult):
        self.model_id = result.model_id
        self.capabilities = EngineCapabilities()
        self._result = result

    def diarize(self, audio: AudioInput) -> DiarizationResult:
        return self._result


def _atomic_json(path: Path, data: dict[str, Any], *, overwrite: bool, jsonl: bool) -> None:
    """Stage in same directory, fsync, then publish. No partial result on failure.

    overwrite=False uses an atomic hard-link create-if-absent on Windows NTFS and
    other filesystems with hard-link support. No TOCTOU overwrite of an existing
    result even if another process creates it during inference.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    content = (json.dumps(data, ensure_ascii=False, allow_nan=False,
                          indent=None if jsonl else 2) + '\n').encode('utf-8')
    fd, temp_name = tempfile.mkstemp(prefix=f'.{path.stem}.tmp_', dir=str(path.parent))
    temp_path = Path(temp_name)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        if overwrite:
            os.replace(temp_path, path)
        else:
            os.link(temp_path, path)  # Atomic failure if destination exists.
    finally:
        temp_path.unlink(missing_ok=True)


class AtomicStatusFile:
    """Write latest status separately from final transcript for UI polling."""
    def __init__(self, path: str | Path):
        self.path = Path(path)

    def __call__(self, snapshot: ProcessingSnapshot) -> None:
        _atomic_json(self.path, snapshot.as_dict(), overwrite=True, jsonl=False)


class ErrorHandler:
    """Convert internal exceptions into stable, user-safe error codes."""

    @staticmethod
    def classify(stage: ProcessingStatus, exception: Exception) -> ProcessingFailure:
        if isinstance(exception, FileExistsError):
            code, message = ErrorCode.OUTPUT_EXISTS, 'Файл результата уже существует'
        elif stage == ProcessingStatus.CREATED:
            code, message = ErrorCode.OUTPUT_FAILED, 'Некорректный путь результата'
        elif stage == ProcessingStatus.PREPROCESSING:
            code, message = ErrorCode.INVALID_AUDIO, 'Ошибка подготовки аудио'
        elif stage == ProcessingStatus.ASR_PROCESSING:
            code, message = ErrorCode.ASR_FAILED, 'Ошибка распознавания речи'
        elif stage == ProcessingStatus.DIARIZATION_PROCESSING:
            code, message = ErrorCode.DIARIZATION_FAILED, 'Ошибка определения говорящих'
        elif stage == ProcessingStatus.MERGING and isinstance(exception, OSError):
            code, message = ErrorCode.OUTPUT_FAILED, 'Не удалось записать результат'
        elif stage == ProcessingStatus.MERGING:
            code, message = ErrorCode.INVALID_RESULT, 'Некорректная структура расшифровки'
        else:
            code, message = ErrorCode.INTERNAL_ERROR, 'Ошибка обработки'
        return ProcessingFailure(code, stage, message)


class ProcessingManager:
    """Single-use coordinator for A3 + two A2 engines + unchanged A2 assembler.

    Cancellation is cooperative: checkpoints before and after each synchronous
    engine invocation, not during execution of a native ONNX session.
    """

    def __init__(self, asr: AsrEngine, diarization: DiarizationEngine, *,
                 preprocessor: Any = None, config: PipelineConfig | None = None,
                 cancellation: CancellationToken | None = None,
                 on_progress: Callable[[ProcessingSnapshot], None] | None = None,
                 record_id: str):
        self.asr = asr
        self.diarization = diarization
        self.preprocessor = preprocessor or AudioPreprocessor()
        self.config = config or PipelineConfig()
        self.cancellation = cancellation or CancellationToken()
        self.tracker = ProgressTracker(record_id, callback=on_progress)
        self._used = False
        self._started = None

    @property
    def status(self) -> ProcessingSnapshot:
        return self.tracker.snapshot

    def cancel(self) -> None:
        self.cancellation.cancel()

    def _checkpoint(self) -> None:
        self.cancellation.check()

    def run(self, audio_path: str | Path, *, output: str | Path | None = None,
            overwrite: bool = False) -> dict[str, Any]:
        if self._used:
            raise RuntimeError('ProcessingManager is single-use; create a new instance per job')
        self._used = True
        self._started = time.perf_counter()
        state = ProcessingStatus.CREATED
        try:
            if output is not None:
                target = Path(output)
                if target.suffix.lower() not in ('.json', '.jsonl'):
                    raise ValueError('Output must have .json or .jsonl extension')
                if target.exists() and not overwrite:
                    raise FileExistsError(f'Output already exists: {target}')
            self._checkpoint()
            state = ProcessingStatus.PREPROCESSING
            self.tracker.update(state)
            audio = self.preprocessor.load(audio_path, self.status.record_id)
            if not isinstance(audio, AudioInput):
                raise TypeError('AudioPreprocessor must return canonical AudioInput')
            self._checkpoint()
            state = ProcessingStatus.ASR_PROCESSING
            self.tracker.update(state)
            asr_result = self.asr.recognize(audio)
            if not isinstance(asr_result, AsrResult) or asr_result.model_id != self.asr.model_id:
                raise ValueError('ASR returned an invalid result or mismatched model_id')
            self._checkpoint()
            state = ProcessingStatus.DIARIZATION_PROCESSING
            self.tracker.update(state)
            diar_result = self.diarization.diarize(audio)
            if not isinstance(diar_result, DiarizationResult) or diar_result.model_id != self.diarization.model_id:
                raise ValueError('Diarization returned invalid result or mismatched model_id')
            self._checkpoint()
            state = ProcessingStatus.MERGING
            self.tracker.update(state)
            doc = LocalAudioProcessor(_AsrResultAdapter(asr_result),
                                      _DiarResultAdapter(diar_result), self.config).process(audio)
            validate_transcript(doc, audio)
            self._checkpoint()
            doc['wall_seconds'] = round(time.perf_counter() - self._started, 3)
            doc['wall_rtf'] = round(doc['wall_seconds'] / audio.duration, 4)
            if output is not None:
                state = ProcessingStatus.MERGING
                _atomic_json(Path(output), doc, overwrite=overwrite,
                             jsonl=Path(output).suffix.lower() == '.jsonl')
            self.tracker.update(ProcessingStatus.COMPLETED, result_id=doc['result_id'])
            return doc
        except ProcessingCancelled:
            self.tracker.update(ProcessingStatus.CANCELLED)
            raise
        except Exception as ex:
            failure = ErrorHandler.classify(state, ex)
            LOG.debug('A6 processing failed (code=%s)', failure.code.value, exc_info=True)
            self.tracker.update(ProcessingStatus.FAILED,
                                error_code=failure.code.value,
                                error_message=str(failure))
            raise failure from ex
