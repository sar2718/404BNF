"""Source audio -> canonical mono 16-kHz float32 AudioInput or bounded chunks.

No ML dependencies. Only NumPy and local ffmpeg/ffprobe binaries are used.
A source file's SHA-256 is computed over its original bytes BEFORE decoding.
Streaming chunks carry absolute sample offsets; chunk-wise ASR/diarization and
boundary reconciliation are NOT implemented here.
"""
from __future__ import annotations

import json
import math
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import numpy as np

from .audio import sha256_file
from .contracts import AudioInput

SAMPLE_RATE = 16_000
ALLOWED_EXTENSIONS = frozenset({'.wav', '.mp3', '.m4a', '.aac', '.flac', '.ogg', '.opus',
                                '.mp4', '.3gp', '.webm'})


class AudioPreprocessError(ValueError):
    """Invalid, unsupported, or failed-to-decode audio."""


@dataclass(frozen=True)
class AudioSource:
    record_id: str
    original_path: Path
    original_sha256: str
    source_sample_rate: int
    source_channels: int
    codec: str
    reported_duration: float | None


@dataclass(frozen=True)
class AudioChunk:
    """A non-overlapping canonical chunk; start_sample is an absolute offset."""
    start_sample: int
    samples: np.ndarray
    sample_rate: int = SAMPLE_RATE

    @property
    def start(self) -> float:
        return self.start_sample / self.sample_rate

    @property
    def end(self) -> float:
        return (self.start_sample + len(self.samples)) / self.sample_rate


class AudioPreprocessor:
    def __init__(self, *, ffmpeg: str = 'ffmpeg', ffprobe: str = 'ffprobe',
                 max_file_bytes: int = 1024 * 1024 * 1024,
                 max_full_seconds: float = 30 * 60,
                 max_stream_seconds: float = 4 * 3600,
                 clip_decoder_overshoot: bool = True):
        self.ffmpeg = self._executable(ffmpeg)
        self.ffprobe = self._executable(ffprobe)
        if max_file_bytes <= 0 or max_full_seconds <= 0 or max_stream_seconds <= 0:
            raise ValueError('All audio resource limits must be positive')
        if max_full_seconds > max_stream_seconds:
            raise ValueError('Full-audio limit cannot exceed streaming limit')
        self.max_file_bytes = int(max_file_bytes)
        self.max_full_seconds = float(max_full_seconds)
        self.max_stream_seconds = float(max_stream_seconds)
        self.clip_decoder_overshoot = clip_decoder_overshoot

    @staticmethod
    def _executable(name: str) -> str:
        path = shutil.which(name)
        if not path:
            raise FileNotFoundError(f'Missing {name!r}. Install FFmpeg and add ffmpeg/ffprobe to PATH.')
        return path

    def inspect(self, path: str | Path, record_id: str) -> AudioSource:
        if (not isinstance(record_id, str) or not record_id.strip()
                or record_id.strip() in ('.', '..')):
            raise ValueError('A nonempty record_id is required')
        file = Path(path).expanduser().resolve(strict=True)
        if not file.is_file():
            raise AudioPreprocessError(f'Not an audio file: {file}')
        if file.suffix.lower() not in ALLOWED_EXTENSIONS:
            raise AudioPreprocessError(f'Unsupported file extension: {file.suffix}')
        size = file.stat().st_size
        if size == 0 or size > self.max_file_bytes:
            raise AudioPreprocessError('Empty file or source file exceeds configured size limit')
        cmd = [self.ffprobe, '-v', 'error', '-select_streams', 'a:0',
               '-show_entries', 'stream=codec_name,sample_rate,channels:format=duration',
               '-of', 'json', '-i', str(file)]
        try:
            res = subprocess.run(cmd, capture_output=True, timeout=30, check=False)
        except subprocess.TimeoutExpired as ex:
            raise AudioPreprocessError('ffprobe timed out') from ex
        if res.returncode != 0:
            raise AudioPreprocessError('ffprobe could not read audio: ' +
                                       res.stderr.decode('utf-8', 'replace')[-800:])
        try:
            metadata = json.loads(res.stdout)
            stream = metadata['streams'][0]
            sr, ch = int(stream['sample_rate']), int(stream['channels'])
        except (KeyError, IndexError, ValueError, TypeError) as ex:
            raise AudioPreprocessError('No decodable audio stream in file') from ex
        if sr <= 0 or ch <= 0:
            raise AudioPreprocessError('Invalid source sample rate or channel count')
        raw_duration = metadata.get('format', {}).get('duration')
        reported_duration = None
        if raw_duration is not None:
            try:
                duration = float(raw_duration)
                if math.isfinite(duration) and duration > 0:
                    reported_duration = duration
            except (ValueError, TypeError):
                pass
        if reported_duration is not None and reported_duration > self.max_stream_seconds + 0.01:
            raise AudioPreprocessError('Audio exceeds maximum allowed streaming duration')
        return AudioSource(record_id=record_id, original_path=file,
                           original_sha256=sha256_file(file),
                           source_sample_rate=sr, source_channels=ch,
                           codec=str(stream.get('codec_name', 'unknown')),
                           reported_duration=reported_duration)

    def iter_chunks(self, source: AudioSource, *, chunk_seconds: float = 20.0) -> Iterator[AudioChunk]:
        """Decode streaming to sequential float32 sample chunks (no window overlap).

        Consumers must fully iterate this generator; errors at end-of-file cause the
        final iteration to raise. If returning early, explicitly close the iterator.
        """
        if not math.isfinite(chunk_seconds) or not 0.01 <= chunk_seconds <= 300:
            raise ValueError('chunk_seconds must be in [0.01, 300]')
        chunk_samples = max(1, round(chunk_seconds * SAMPLE_RATE))
        chunk_bytes = chunk_samples * 4
        cmd = [self.ffmpeg, '-hide_banner', '-loglevel', 'error', '-nostdin',
               '-i', str(source.original_path), '-map', '0:a:0',
               '-vn', '-sn', '-dn', '-ac', '1', '-ar', str(SAMPLE_RATE),
               '-c:a', 'pcm_f32le', '-f', 'f32le', 'pipe:1']
        # Deterministic arithmetic mean of all source channels. Unlike the
        # FFmpeg default downmix matrix, this is straightforward to reproduce
        # in Kotlin/C++ and avoids accidentally amplifying stereo audio.
        if source.source_channels > 1:
            n = source.source_channels
            weight = format(1.0 / n, '.16g')
            expression = '+'.join(f'{weight}*c{i}' for i in range(n))
            cmd[cmd.index('-ac'):cmd.index('-ac')] = ['-af', f'pan=mono|c0={expression}']
        position = 0
        max_samples = int(self.max_stream_seconds * SAMPLE_RATE)
        with tempfile.TemporaryFile(mode='w+b') as stderr:
            try:
                process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=stderr)
            except OSError as ex:
                raise AudioPreprocessError(f'Failed to start ffmpeg: {ex}') from ex
            completed = False
            try:
                assert process.stdout is not None
                while True:
                    raw = process.stdout.read(chunk_bytes)
                    if not raw:
                        break
                    if len(raw) % 4:
                        raise AudioPreprocessError('Decoder emitted an incomplete float32 sample')
                    samples = np.frombuffer(raw, dtype='<f4').astype(np.float32, copy=True)
                    if not np.all(np.isfinite(samples)):
                        raise AudioPreprocessError('Decoded audio contains NaN/Infinity')
                    if np.any(np.abs(samples) > 1):
                        if not self.clip_decoder_overshoot:
                            raise AudioPreprocessError('Decoded audio amplitude exceeds [-1, 1]')
                        # Lossy decoders can overshoot very slightly. Clip for the
                        # same canonical amplitude contract used by AudioInput/Android.
                        np.clip(samples, -1, 1, out=samples)
                    if position + len(samples) > max_samples:
                        raise AudioPreprocessError('Decoded audio exceeds maximum streaming duration')
                    yield AudioChunk(start_sample=position,
                                     samples=np.ascontiguousarray(samples))
                    position += len(samples)
                process.stdout.close()
                code = process.wait(timeout=30)
                if code != 0:
                    stderr.seek(0)
                    detail = stderr.read(3000).decode('utf-8', 'replace')
                    raise AudioPreprocessError('ffmpeg failed: ' + detail[-800:])
                if position == 0:
                    raise AudioPreprocessError('Audio decoded to zero samples')
                # Source integrity: reject files modified after initial hashing.
                # Only after a full iteration is the stream considered verified.
                try:
                    stable_hash = sha256_file(source.original_path)
                except OSError as ex:
                    raise AudioPreprocessError('Source disappeared during decoding') from ex
                if stable_hash != source.original_sha256:
                    raise AudioPreprocessError('Source audio changed during decoding (SHA-256 mismatch)')
                completed = True
            finally:
                if not completed and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                if process.stdout is not None and not process.stdout.closed:
                    process.stdout.close()

    def load(self, path: str | Path, record_id: str) -> AudioInput:
        """Load full audio for the inference engines; explicitly bounded in duration."""
        source = self.inspect(path, record_id)
        if source.reported_duration is not None and source.reported_duration > self.max_full_seconds + 0.1:
            raise AudioPreprocessError('Full-audio length limit exceeded. Use iter_chunks for long recordings.')
        chunks = []
        count = 0
        max_samples = int(self.max_full_seconds * SAMPLE_RATE)
        for chunk in self.iter_chunks(source):
            count += len(chunk.samples)
            if count > max_samples:
                raise AudioPreprocessError('Full-audio length limit exceeded. Use iter_chunks for long recordings.')
            chunks.append(chunk.samples)
        samples = np.ascontiguousarray(np.concatenate(chunks))
        return AudioInput(record_id=record_id, samples=samples, sample_rate=SAMPLE_RATE,
                          original_path=source.original_path,
                          original_sha256=source.original_sha256)
