"""Model-independent pipeline: internal contracts -> transcript schema v1.

Speaker assignment uses the v2 time-overlap heuristic. It is intentionally not
an ML model. This module does not load source code of any concrete engine.
"""
from __future__ import annotations

import math
import uuid
from dataclasses import dataclass
from typing import Any

from .contracts import AudioInput, AsrEngine, AsrResult, DiarizationEngine, DiarizationResult

UNKNOWN = 'UNKNOWN'


@dataclass(frozen=True)
class PipelineConfig:
    merge_gap_seconds: float = 0.75
    max_assignment_distance_seconds: float = 0.35
    ambiguity_ratio: float = 0.90

    def __post_init__(self) -> None:
        if (self.merge_gap_seconds < 0 or self.max_assignment_distance_seconds < 0
                or not 0 < self.ambiguity_ratio <= 1):
            raise ValueError('Invalid assignment/merge configuration')


def _turns(result: DiarizationResult, duration: float) -> list[dict]:
    out = []
    for turn in result.turns:
        a, b = float(turn.start), float(turn.end)
        if (not math.isfinite(a) or not math.isfinite(b) or a >= b
                or a < -1.0 or b > duration + 1.0 or b <= 0 or a >= duration):
            raise ValueError(f'Invalid diarization interval {turn!r} for {duration}s audio')
        if not turn.speaker_id or turn.speaker_id == UNKNOWN:
            raise ValueError('Diarization segment needs a real speaker_id')
        out.append({'start': max(0.0, a), 'end': min(duration, b),
                    'speaker_id': turn.speaker_id})
    return sorted(out, key=lambda x: (x['start'], x['end'], x['speaker_id']))


def _words(result: AsrResult, duration: float) -> list[dict]:
    if result.text.strip() and not result.words:
        raise ValueError('ASR has text but no timed words; add alignment in ASR adapter')
    words = []
    for word in result.words:
        if word.start is None or word.end is None:
            raise ValueError('ASR returned untimed word; cannot infer speaker assignment')
        a, b = float(word.start), float(word.end)
        if (not math.isfinite(a) or not math.isfinite(b)
                or a < -0.02 or a > b or b > duration + 0.02):
            raise ValueError(f'Invalid ASR word timing: {word!r}')
        if not isinstance(word.text, str) or not word.text:
            raise ValueError('Empty ASR word')
        words.append({'text': word.text, 'start': max(0.0, a), 'end': min(duration, b),
                      'speaker_id': None, 'timing_source': word.timing_source})
    if any(words[i]['start'] > words[i + 1]['start'] for i in range(len(words) - 1)):
        raise ValueError('ASR words are not ordered by time')
    return words


def assign_speaker(word: dict, turns: list[dict], cfg: PipelineConfig) -> dict:
    """Keep v2 semantics: ambiguous simultaneous speakers => UNKNOWN."""
    start, end = float(word['start']), float(word['end'])
    if end <= start:
        end = start + 0.04
    mid = (start + end) / 2
    support: dict[str, float] = {}
    speakers_at_mid: set[str] = set()
    for turn in turns:
        speaker = turn['speaker_id']
        overlap = max(0.0, min(end, turn['end']) - max(start, turn['start']))
        support[speaker] = support.get(speaker, 0.0) + overlap
        if turn['start'] <= mid <= turn['end']:
            speakers_at_mid.add(speaker)
    candidates = sorted(((sp, dur) for sp, dur in support.items() if dur > 0),
                        key=lambda item: (-item[1], item[0]))
    speaker = UNKNOWN
    ambiguous = False
    if candidates:
        if len(candidates) > 1 and candidates[1][1] >= candidates[0][1] * cfg.ambiguity_ratio:
            ambiguous = True
        else:
            speaker = candidates[0][0]
    else:
        near = []
        for turn in turns:
            dist = max(turn['start'] - mid, mid - turn['end'], 0.0)
            if dist <= cfg.max_assignment_distance_seconds:
                near.append((dist, turn['speaker_id']))
        if near:
            near.sort()
            if len(near) > 1 and abs(near[0][0] - near[1][0]) < 0.04 and near[0][1] != near[1][1]:
                ambiguous = True
            else:
                speaker = near[0][1]
    return {**word, 'speaker_id': speaker,
            'overlap': len(speakers_at_mid) > 1, 'speaker_ambiguous': ambiguous}


def _combine_words(words: list[dict], gap: float) -> list[dict]:
    groups: list[dict] = []
    for word in words:
        same = (groups and groups[-1]['speaker_id'] == word['speaker_id']
                and word['start'] - groups[-1]['end'] <= gap)
        if same:
            group = groups[-1]
            group['end'] = max(group['end'], word['end'])
            group['words'].append(word)
            group['overlap'] |= word['overlap']
            group['speaker_ambiguous'] |= word['speaker_ambiguous']
        else:
            groups.append({'start': word['start'], 'end': word['end'],
                           'speaker_id': word['speaker_id'], 'overlap': word['overlap'],
                           'speaker_ambiguous': word['speaker_ambiguous'], 'words': [word]})
    for index, group in enumerate(groups, 1):
        group['id'] = f'segment_{index:04d}'
        group['text'] = ' '.join(w['text'] for w in group.pop('words'))
        group['start'] = round(group['start'], 3)
        group['end'] = round(group['end'], 3)
    return groups


class LocalAudioProcessor:
    def __init__(self, asr: AsrEngine, diarization: DiarizationEngine,
                 config: PipelineConfig | None = None):
        self.asr = asr
        self.diarization = diarization
        self.config = config or PipelineConfig()

    def process(self, audio: AudioInput) -> dict[str, Any]:
        asr_result = self.asr.recognize(audio)
        diar_result = self.diarization.diarize(audio)
        if asr_result.model_id != self.asr.model_id or diar_result.model_id != self.diarization.model_id:
            raise ValueError('Model output model_id does not match engine')
        turns = _turns(diar_result, audio.duration)
        words = [assign_speaker(w, turns, self.config) for w in _words(asr_result, audio.duration)]
        labels: dict[str, str] = {}
        for turn in turns:
            if turn['speaker_id'] not in labels:
                labels[turn['speaker_id']] = f'Участник {len(labels) + 1}'
        for word in words:
            word['speaker_label'] = labels.get(word['speaker_id'], 'Не определён')
        groups = _combine_words(words, self.config.merge_gap_seconds)
        for group in groups:
            group['speaker_label'] = labels.get(group['speaker_id'], 'Не определён')
        # Public model-independent contract; never invent confidence or accuracy.
        data: dict[str, Any] = {
            'schema_version': 1,
            'id': audio.record_id,
            'result_id': str(uuid.uuid4()),
            'audio_sha256': audio.original_sha256,
            'audio': audio.original_path.name,
            'duration': round(audio.duration, 3),
            'asr_model': asr_result.model_id,
            'diarization': diar_result.model_id,
            'pipeline_version': 2,
            'timing_precision': asr_result.timing_precision,
            'text': asr_result.text,
            'segments': groups,
            'words': words,
            'speaker_labels': labels,
            'speakers_detected': len(labels),
            'words_unassigned': sum(w['speaker_id'] == UNKNOWN for w in words),
            'words_ambiguous': sum(bool(w['speaker_ambiguous']) for w in words),
            'model_metadata': {
                'asr': asr_result.metadata,
                'diarization': diar_result.metadata,
            },
        }
        # Backwards-compatible v2 metadata fields when available. None of these
        # are synthetic timings or measured by this cached postprocessing pass.
        for key in ('asr_chunking', 'chunks', 'inference_seconds', 'rtf'):
            value = asr_result.metadata.get(key)
            if value is not None:
                data[key] = value
        diar_sec = diar_result.metadata.get('inference_seconds')
        if diar_sec is not None:
            data['cached_diarization_seconds'] = diar_sec
        if isinstance(diar_sec, (float, int)) and isinstance(data.get('inference_seconds'), (float, int)):
            data['estimated_total_seconds'] = round(float(diar_sec) + float(data['inference_seconds']), 3)
            data['estimated_total_rtf'] = round(data['estimated_total_seconds'] / audio.duration, 4)
        return data
