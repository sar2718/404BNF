# ML-модели 404BNF: установка и проверка

## Один запуск в PowerShell (Windows, Python 3.13)

Из корня **404BNF-ML-Core**:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```

Команда создаст `.venv`, установит зависимости из `requirements-win-py313.lock` (точные версии из успешного запуска), проверит FFmpeg (при необходимости установит через WinGet), скачает **исходную GigaAM v3 E2E RNN-T FP32** и две модели диаризации, проверит SHA-256 и попробует загрузить GigaAM в режиме `HF_HUB_OFFLINE=1`. **INT8 не используется.**

Нужен интернет **для первого развёртывания** и достаточно места на диске: суммарно приблизительно 1 ГБ для весов плюс библиотеки и временные файлы. Начальные загрузки на других компьютерах выполняются отдельно.

Перед запуском рекомендуется проверить условия использования моделей. На Android этот PowerShell-скрипт не используется.

## Где лежат модели

```text
models/
├── gigaam-v3-e2e-rnnt/
│   ├── config.json
│   ├── v3_e2e_rnnt.yaml
│   ├── v3_e2e_rnnt_vocab.txt
│   ├── v3_e2e_rnnt_encoder.onnx   # FP32
│   ├── v3_e2e_rnnt_decoder.onnx   # FP32
│   ├── v3_e2e_rnnt_joint.onnx     # FP32
│   └── .404bnf-model-manifest.json
└── mobile_diarization/
    ├── silero_vad_v6.2.1.onnx
    └── voxceleb_CAM++.onnx
```

GigaAM берём **из фиксированного commit** `322c3b29492673eb7d0b434bfa9dfb8653e34d02` репозитория `istupakov/gigaam-v3-onnx` с помощью `huggingface_hub.hf_hub_download` (`onnx-asr==0.12.0` загружает только локальные файлы для инференса). ONNX-файлы не коммитятся. У трёх FP32 ONNX-файлов сверяются SHA-256 с опубликованными контрольными суммами; после установки дополнительно проверяется манифест всех шести файлов. Отдельные SHA-256 Silero и CAM++ зафиксированы в `processor_core/model_assets.py` и взяты из предыдущей проверенной сборки.

Репозитории:
- GigaAM ONNX: https://huggingface.co/istupakov/gigaam-v3-onnx
- Silero: https://huggingface.co/bitsydarel/silero-vad-onnx
- WeSpeaker CAM++: https://huggingface.co/Wespeaker/wespeaker-voxceleb-campplus

## Проверка без загрузки

```powershell
.\.venv\Scripts\python.exe tools\model_setup.py verify --check-inference
```

Проверяет хэши файлов и загружает GigaAM с `HF_HUB_OFFLINE=1`; сетевую карту этот тест **не отключает**, но сам загрузчик получает режим офлайн. Для полной проверки без сети можно затем отключить интернет и запустить `run.py` на реальной записи.

```powershell
.\.venv\Scripts\python.exe run.py --audio "C:\путь\к\записи.wav" --output output\test.json
```

**Не используйте буквальный пример `C:\path\to\recording.m4a`** — это не реальный файл. Путь должен вести к существующей записи. Для повторного вывода в тот же JSON добавьте `--overwrite`.

## Если произошла ошибка

- Нет Python 3.13 — установите Python 3.13 и повторите ту же команду.
- FFmpeg установлен, но не виден — перезапустите PyCharm и повторите `setup.ps1`.
- Загрузка прервалась — повторите `setup.ps1`. Исправные Silero/CAM++ не скачиваются заново; частично скачанные файлы GigaAM загрузчик попробует восстановить при повторе. Не удаляйте исправные веса без необходимости.
- SHA-256 не совпал — остановитесь и проверьте источник, **не обходите проверку**.
- `GigaAM is not installed...` — запустите `setup.ps1`; обычный `run.py` больше **не начинает скрытую загрузку GigaAM**.
- Если GigaAM загрузилась лишь частично и повторный запуск не помогает, сначала переименуйте `models/gigaam-v3-e2e-rnnt` в резервный каталог, затем снова вызовите `setup.ps1`.

Параметры скрипта для опытных пользователей: `-SkipFfmpegInstall`, `-SkipModels`, `-SkipOfflineCheck`. По умолчанию устанавливается всё и выполняется офлайн-проверка загрузчика.

## Что коммитим

Коммитим `requirements-win-py313.lock`, `setup.ps1`, `tools/model_setup.py`, `processor_core/model_assets.py`, `requirements.txt`, `MODEL_SETUP.md`, `.gitignore` и весь `processor_core`. **Не коммитим** `.venv/`, файлы моделей, `.404bnf-model-manifest.json`, записи и `output/*.json`.

Проверка успеха инсталлятора **не равна** тестам скорости/точности на другом устройстве; для этого нужна отдельная контрольная запись. Этот пакет предназначен для Python/Windows, а не для установки в Android APK.
