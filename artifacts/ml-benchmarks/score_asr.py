"""Recalculate WER/CER from archived ASR hypotheses and Veche-Bench references."""

import csv
import json
import re
import sys
import wave
from pathlib import Path, PurePosixPath
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent
DATASETS = {"results_sinthezis": "main", "results_realData": "meetings"}


def words(text):
    return re.findall(r"\w+", text.lower().replace("ё", "е"))


def edit_distance(reference, hypothesis):
    previous = list(range(len(hypothesis) + 1))
    for i, expected in enumerate(reference, start=1):
        current = [i]
        for j, actual in enumerate(hypothesis, start=1):
            current.append(min(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + (expected != actual),
            ))
        previous = current
    return previous[-1]


def scores():
    references = {}
    for path in sorted((ROOT / "dataset").glob("*/*.json")):
        record = json.loads(path.read_text(encoding="utf-8-sig"))
        with wave.open(str(path.with_suffix(".wav")), "rb") as audio:
            duration = audio.getnframes() / audio.getframerate()
        references[path.parent.name, record["id"]] = words(record["reference_text"]), duration

    with ZipFile(ROOT / "raw-results.zip") as archive:
        for name in sorted(archive.namelist()):
            path = PurePosixPath(name)
            dataset = DATASETS.get(path.parent.name)
            if not dataset or path.suffix != ".jsonl" or not path.name.startswith(("faster-whisper_", "onnx-asr_")):
                continue
            totals = [0, 0, 0, 0, 0.0, 0.0]
            for line in archive.read(name).decode("utf-8-sig").splitlines():
                if not line.strip():
                    continue
                run = json.loads(line)
                reference, duration = references[dataset, run["id"]]
                hypothesis = words(run["text"])
                ref_chars, hyp_chars = "".join(reference), "".join(hypothesis)
                counts = [
                    edit_distance(reference, hypothesis), len(reference),
                    edit_distance(ref_chars, hyp_chars), len(ref_chars),
                    run["inference_seconds"], duration,
                ]
                yield dataset, path.stem, run["id"], counts
                totals = [a + b for a, b in zip(totals, counts)]
            yield dataset, path.stem, "micro", totals


def main():
    output = csv.writer(sys.stdout)
    output.writerow(("dataset", "run", "recording", "word_errors", "reference_words",
                     "char_errors", "reference_chars", "wer_percent", "cer_percent", "rtf"))
    for dataset, run, recording, counts in scores():
        word_errors, n_words, char_errors, n_chars, seconds, duration = counts
        output.writerow((dataset, run, recording, word_errors, n_words, char_errors, n_chars,
                         f"{100 * word_errors / n_words:.3f}", f"{100 * char_errors / n_chars:.3f}",
                         f"{seconds / duration:.4f}"))


if __name__ == "__main__":
    main()
