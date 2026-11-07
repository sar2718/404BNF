#!/usr/bin/env python3
"""Local 404BNF Python ML runner. No HTTP, cache, or benchmark files."""
from __future__ import annotations

import argparse
import math
import signal
import sys
from pathlib import Path

from processor_core import ModelRegistry
from processor_core.engines import GigaAmAsrEngine, WeSpeakerDiarizationEngine
from processor_core.processing import (AtomicStatusFile, CancellationToken,
                                       ProcessingCancelled, ProcessingFailure,
                                       ProcessingManager)


class LazyAsr:
    model_id = GigaAmAsrEngine.model_id
    capabilities = GigaAmAsrEngine.capabilities
    def __init__(self):
        self.engine = None
    def recognize(self, audio):
        if self.engine is None:
            self.engine = GigaAmAsrEngine()
        return self.engine.recognize(audio)


class LazyDiarization:
    model_id = WeSpeakerDiarizationEngine.model_id
    capabilities = WeSpeakerDiarizationEngine.capabilities
    def __init__(self, args):
        self.args = args
        self.engine = None
    def diarize(self, audio):
        if self.engine is None:
            self.engine = WeSpeakerDiarizationEngine(
                models_dir=self.args.models_dir,
                cluster_distance=self.args.cluster_distance,
                vad_threshold=self.args.vad_threshold,
                window=self.args.window, hop=self.args.hop,
                threads=self.args.threads, num_speakers=self.args.num_speakers,
            )
        return self.engine.diarize(audio)


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description='404BNF: local offline-capable Python ML pipeline')
    ap.add_argument('--audio', type=Path, required=True, help='Audio input: WAV/MP3/M4A/etc.')
    ap.add_argument('--id', dest='record_id', help='Recording ID (default: file stem)')
    ap.add_argument('--output', type=Path, help='Transcript JSON (default: output/<id>.json)')
    ap.add_argument('--status-file', type=Path, help='Optional JSON for stage status')
    ap.add_argument('--cancel-file', type=Path, help='Optional file-based cancellation between stages')
    ap.add_argument('--overwrite', action='store_true')
    ap.add_argument('--models-dir', type=Path, default=Path('models/mobile_diarization'))
    ap.add_argument('--cluster-distance', type=float, default=0.55)
    ap.add_argument('--vad-threshold', type=float, default=0.5)
    ap.add_argument('--window', type=float, default=2.0)
    ap.add_argument('--hop', type=float, default=0.8)
    ap.add_argument('--threads', type=int, default=2)
    ap.add_argument('--num-speakers', type=int, default=None,
                    help='Specify only if known from user; default autodetect')
    args = ap.parse_args(argv)
    args.record_id = args.record_id or args.audio.stem
    args.output = args.output or Path('output') / f'{args.record_id}.json'
    if args.output.suffix.lower() != '.json':
        ap.error('Only .json output is supported in the production runner')
    paths = [args.audio.resolve(), args.output.resolve()]
    for name in ('status_file','cancel_file'):
        path = getattr(args,name)
        if path is not None:
            path = path.resolve()
            if path in paths:
                ap.error(f'--{name.replace("_","-")} must not reuse another input/output path')
            paths.append(path)
    if not math.isfinite(args.cluster_distance) or not 0 < args.cluster_distance < 2:
        ap.error('cluster distance must be (0,2)')
    return args


def execute(args):
    registry = ModelRegistry()
    registry.register_asr(LazyAsr.model_id, LazyAsr)
    registry.register_diarization(LazyDiarization.model_id, lambda: LazyDiarization(args))
    asr, diar = registry.resolve(LazyAsr.model_id, LazyDiarization.model_id)
    cancellation = CancellationToken(
        external_check=(lambda: args.cancel_file.exists()) if args.cancel_file else None)
    status_writer = AtomicStatusFile(args.status_file) if args.status_file else None

    def progress(snapshot):
        print(f'{snapshot.status.value}: {snapshot.stage_number}/{snapshot.stage_total}', flush=True)
        if status_writer is not None:
            status_writer(snapshot)

    manager = ProcessingManager(asr, diar, record_id=args.record_id,
                                cancellation=cancellation, on_progress=progress)
    def on_sigint(signum, frame):
        print('Cancel requested: current ONNX inference must finish first.', flush=True)
        manager.cancel()

    old_handler = signal.getsignal(signal.SIGINT)
    signal.signal(signal.SIGINT, on_sigint)
    try:
        result = manager.run(args.audio, output=args.output, overwrite=args.overwrite)
        print(f'RESULT: {args.output}; words={len(result["words"])}; '
              f'segments={len(result["segments"])}; '
              f'speakers={result["speakers_detected"]}', flush=True)
        return result
    finally:
        signal.signal(signal.SIGINT, old_handler)


def main(argv=None):
    args = parse_args(argv)
    try:
        execute(args)
    except ProcessingCancelled:
        print('CANCELLED: no partial transcript saved', file=sys.stderr)
        return 2
    except ProcessingFailure as exc:
        print(f'FAILED [{exc.code.value}]: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
