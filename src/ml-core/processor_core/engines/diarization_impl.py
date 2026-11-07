"""Internal Silero VAD + WeSpeaker CAM++ ONNX implementation.

Production extraction of the IDENTICAL inference and clustering algorithms
from the previously tested mobile_diarization.py. No benchmark/manifest code.
"""
from __future__ import annotations
import math
from pathlib import Path

SAMPLE_RATE = 16000

class SileroOnnx:
    """Direct upstream Silero v6 ONNX: 512 samples + 64 previous samples.

    input: [1,576]; state: [2,1,128]; sr: int64 scalar.
    Internal state resets for EACH distinct recording.
    """

    def __init__(self, model: Path, threads: int = 2):
        import onnxruntime as ort
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        opts.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(model), sess_options=opts, providers=['CPUExecutionProvider'])
        names = {v.name for v in self.session.get_inputs()}
        if not {'input', 'state', 'sr'}.issubset(names):
            raise ValueError(f'Неожиданные входы Silero: {names}')

    def probabilities(self, samples):
        import numpy as np
        self_state = np.zeros((2, 1, 128), dtype=np.float32)
        context = np.zeros(64, dtype=np.float32)
        result = []
        for start in range(0, len(samples), 512):
            window = np.asarray(samples[start:start + 512], dtype=np.float32)
            if len(window) < 512:
                window = np.pad(window, (0, 512 - len(window)))
            block = np.concatenate([context, window]).reshape(1, 576)
            output = self.session.run(None, {
                'input': block, 'state': self_state,
                'sr': np.array(SAMPLE_RATE, dtype=np.int64),
            })
            prob = float(np.asarray(output[0]).ravel()[0])
            if not math.isfinite(prob) or not 0 <= prob <= 1:
                raise ValueError('Silero вернула некорректную вероятность')
            self_state = np.asarray(output[1], dtype=np.float32)
            context = window[-64:].copy()
            result.append(prob)
        return result


def speech_intervals(probs: list[float], duration: float, threshold: float = 0.5,
                     min_silence: float = 0.25, min_speech: float = 0.25,
                     pad: float = 0.10) -> list[tuple[float, float]]:
    """Two-threshold hysteresis and merging; time resolution is 32 ms."""
    if not 0 < threshold < 1:
        raise ValueError('VAD threshold должен быть в (0,1)')
    if duration <= 0:
        return []
    hop = 512 / SAMPLE_RATE
    low = max(0.05, threshold - 0.15)
    active = False
    onset = 0.0
    silence_start = None
    raw = []
    for i, prob in enumerate(probs):
        t = min(i * hop, duration)
        if not active:
            if prob >= threshold:
                onset, active, silence_start = t, True, None
        else:
            if prob < low:
                if silence_start is None:
                    silence_start = t
                if (i + 1) * hop - silence_start >= min_silence:
                    end = max(onset, silence_start)
                    if end - onset >= min_speech:
                        raw.append((onset, end))
                    active, silence_start = False, None
            else:
                silence_start = None
    if active:
        end = duration if silence_start is None else min(duration, silence_start)
        if end - onset >= min_speech:
            raw.append((onset, end))
    padded = [(max(0., a - pad), min(duration, b + pad)) for a, b in raw]
    joined = []
    for a, b in padded:
        if joined and a <= joined[-1][1]:
            joined[-1] = (joined[-1][0], max(joined[-1][1], b))
        else:
            joined.append((a, b))
    return joined


def make_windows(speech: list[tuple[float, float]], size: float = 2.0,
                 hop: float = 0.8, min_embed: float = 0.55) -> list[dict]:
    """Use contiguous VAD turns; overlapping short replies remain challenging.

    Each window has parent VAD interval index so speaker assignment never
    borrows embeddings from unrelated pause-separated speech in this stage.
    """
    if size <= 0 or hop <= 0 or min_embed <= 0:
        raise ValueError('Неверные размеры окон')
    windows = []
    for part, (start, end) in enumerate(speech):
        if end - start < min_embed:
            continue
        if end - start <= size:
            cuts = [(start, end)]
        else:
            cuts = []
            pos = start
            while pos + size < end:
                cuts.append((pos, pos + size))
                pos += hop
            last = (end - size, end)
            if not cuts or last[0] - cuts[-1][0] > .10:
                cuts.append(last)
        for a, b in cuts:
            windows.append({'start': a, 'end': b, 'part': part, 'center': (a + b) / 2})
    return windows


class WespeakerOnnx:
    """CAM++ with Kaldi FBank 80, 25/10ms Hamming, mean-only CMN.

    Matches upstream wespeaker/bin/infer_onnx.py processing:
    waveform * 32768, dither=0, use_energy=False, hamming window, CMN.
    """

    def __init__(self, path: Path, threads: int = 2):
        import onnxruntime as ort
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        opts.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(path), sess_options=opts, providers=['CPUExecutionProvider'])
        names = {x.name for x in self.session.get_inputs()}
        if 'feats' not in names:
            raise ValueError(f'CAM++ ожидала вход feats, получено: {names}')

    @staticmethod
    def features(waveform):
        import numpy as np
        import kaldi_native_fbank as knf
        if len(waveform) < int(SAMPLE_RATE * 0.45):
            raise ValueError('Слишком короткая реплика для надёжного CAM++ embedding')
        opts = knf.FbankOptions()
        opts.frame_opts.samp_freq = SAMPLE_RATE
        opts.frame_opts.dither = 0.0
        opts.frame_opts.window_type = 'hamming'
        opts.frame_opts.frame_length_ms = 25.0
        opts.frame_opts.frame_shift_ms = 10.0
        opts.frame_opts.snip_edges = True
        opts.mel_opts.num_bins = 80
        opts.use_energy = False
        fbank = knf.OnlineFbank(opts)
        # TorchAudio WeSpeaker example feeds PCM-scaled floats into Kaldi FBank.
        fbank.accept_waveform(SAMPLE_RATE, (np.asarray(waveform) * 32768.0).tolist())
        fbank.input_finished()
        n = fbank.num_frames_ready
        if n < 10:
            raise ValueError('Недостаточно FBank кадров')
        x = np.stack([np.asarray(fbank.get_frame(i), dtype=np.float32) for i in range(n)])
        x -= x.mean(axis=0, keepdims=True)
        return x.reshape(1, n, 80)

    def embed(self, waveform):
        import numpy as np
        feats = self.features(waveform)
        result = self.session.run(None, {'feats': feats})
        emb = np.asarray(result[0], dtype=np.float32).reshape(-1)
        if not np.all(np.isfinite(emb)):
            raise ValueError('CAM++ вернула NaN/Inf')
        norm = float(np.linalg.norm(emb))
        if norm <= 1e-8:
            raise ValueError('CAM++ вернула пустой embedding')
        return emb / norm


def cluster_embeddings(embeddings, *, distance: float = 0.35,
                       num_speakers: int | None = None) -> list[int]:
    import numpy as np
    from scipy.cluster.hierarchy import fcluster, linkage
    from scipy.spatial.distance import pdist
    if not 0 < distance < 2:
        raise ValueError('--cluster-distance должен быть между 0 и 2')
    n = len(embeddings)
    if not n:
        return []
    if n == 1:
        return [0]
    data = np.asarray(embeddings, dtype=np.float32)
    if data.ndim != 2 or not np.all(np.isfinite(data)):
        raise ValueError('Некорректный массив embeddings')
    # Each embedding is already L2 normalized; cosine distance=1-cosine.
    condensed = pdist(data, metric='cosine')
    if not np.all(np.isfinite(condensed)):
        raise ValueError('Некорректные косинусные расстояния')
    tree = linkage(condensed, method='average')
    if num_speakers is None:
        clusters = fcluster(tree, t=distance, criterion='distance')
    else:
        if not 1 <= num_speakers <= n:
            raise ValueError('Число голосов должно быть от 1 до числа окон')
        if num_speakers == 1:
            clusters = np.ones(n, dtype=int)
        else:
            # cut_tree returns EXACT k; fcluster(maxclust) can return fewer.
            from scipy.cluster.hierarchy import cut_tree
            clusters = cut_tree(tree, n_clusters=[num_speakers])[:, 0]
    mapping = {}
    ordered = []
    for c in clusters:
        c = int(c)
        if c not in mapping:
            mapping[c] = len(mapping)
        ordered.append(mapping[c])
    return ordered


def assign_timeline(speech: list[tuple[float, float]], windows: list[dict],
                    labels: list[int], duration: float,
                    quantum: float = 0.16) -> list[dict]:
    """Assign each VAD subinterval to closest embedded window within that turn.

    No reference to any GT. No assumption that VAD can recover actual overlaps.
    """
    if len(windows) != len(labels):
        raise ValueError('Метки и окна различаются по длине')
    turns = []
    for part, (a, b) in enumerate(speech):
        matching = [(w['center'], labels[i]) for i, w in enumerate(windows) if w['part'] == part]
        if not matching:
            turns.append({'start': round(a, 3), 'end': round(b, 3), 'speaker_id': 'UNKNOWN'})
            continue
        t = a
        while t < b - 1e-8:
            stop = min(t + quantum, b)
            mid = (t + stop) / 2
            _, assigned = min(matching, key=lambda p: (abs(p[0] - mid), p[0]))
            sid = f'SPEAKER_{assigned:02d}'
            if turns and turns[-1]['speaker_id'] == sid and abs(turns[-1]['end'] - t) < 0.001:
                turns[-1]['end'] = round(stop, 3)
            else:
                turns.append({'start': round(t, 3), 'end': round(stop, 3), 'speaker_id': sid})
            t = stop
    return turns
