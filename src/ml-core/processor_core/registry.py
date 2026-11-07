"""Small explicit registry: replace/add model by registering an adapter factory."""
from __future__ import annotations
from collections.abc import Callable
from typing import Any

from .contracts import AsrEngine, DiarizationEngine


class ModelRegistry:
    def __init__(self) -> None:
        self._asr: dict[str, Callable[[], AsrEngine]] = {}
        self._diar: dict[str, Callable[[], DiarizationEngine]] = {}

    def register_asr(self, model_id: str, factory: Callable[[], AsrEngine]) -> None:
        if not model_id or model_id in self._asr:
            raise ValueError(f'Duplicate/empty ASR model_id: {model_id!r}')
        self._asr[model_id] = factory

    def register_diarization(self, model_id: str, factory: Callable[[], DiarizationEngine]) -> None:
        if not model_id or model_id in self._diar:
            raise ValueError(f'Duplicate/empty diarization model_id: {model_id!r}')
        self._diar[model_id] = factory

    def resolve(self, asr_id: str, diar_id: str) -> tuple[AsrEngine, DiarizationEngine]:
        if asr_id not in self._asr:
            raise KeyError(f'Unknown ASR {asr_id!r}; available: {sorted(self._asr)}')
        if diar_id not in self._diar:
            raise KeyError(f'Unknown diarization {diar_id!r}; available: {sorted(self._diar)}')
        asr, diar = self._asr[asr_id](), self._diar[diar_id]()
        if asr.model_id != asr_id or diar.model_id != diar_id:
            raise ValueError('Factory returned model with a different model_id')
        return asr, diar

    def list_models(self) -> dict[str, list[str]]:
        return {'asr': sorted(self._asr), 'diarization': sorted(self._diar)}
