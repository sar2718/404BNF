# 404BNF — ML Core (чистый Python-проект)

Назначение: **локальное Python ML-ядро** GigaAM + Silero VAD + WeSpeaker CAM++ с единым JSON v1. Это компактная сборка проверенного A2–A6 без бенчмарков, датасетов, кэшей и экспериментальных запусков. Она служит рабочей Python-реализацией и эталоном для Android-порта. **В APK Python-код не включается.** Ни FastAPI, ни HTTP, ни серверной обработки здесь нет.

## Быстрая установка на Windows (Python 3.13)

Для нового участника команды, находясь в корне проекта:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```

Скрипт устанавливает фиксированные версии библиотек (`requirements-win-py313.lock`), FFmpeg (если отсутствует), исходные FP32-веса GigaAM (не INT8), Silero и CAM++. Модели загружаются один раз в `models/` и проверяются по контрольным суммам. Повторная установка исправные файлы не скачивает. Дополнительные детали — в [MODEL_SETUP.md](MODEL_SETUP.md). После установки `run.py` использует **локальные веса** и не инициирует загрузку GigaAM.

## 1. Создать отдельный проект в PyCharm

1. Нынешний `C:\Users\Misha\Desktop\localProjects\404BNF` **не трогать** — это лаборатория с тестами, экспериментами и результатами.
2. Распаковать архив рядом с ним, чтобы появилась папка `C:\Users\Misha\Desktop\localProjects\404BNF-ML-Core`.
3. В PyCharm открыть `File → Open` и выбрать **`404BNF-ML-Core`**. Лучше открыть в новом окне (`New Window`).
4. Создать новое окружение для нового проекта:

   ```powershell
   py -3.13 -m venv .venv
   .\.venv\Scripts\python.exe -m pip install --upgrade pip
   .\.venv\Scripts\python.exe -m pip install -r requirements.txt
   ```

   Если PyCharm не выбрал окружение автоматически: Settings → Project → Python Interpreter → Add Interpreter → Existing, выбрать `.venv\Scripts\python.exe`.

5. `ffmpeg -version` и `ffprobe -version` должны работать в терминале PyCharm. FFmpeg уже установлен на машине; второй раз не устанавливать.

## 2. Установка весов ONNX (автоматически)

**Предпочтительно:** запустите `setup.ps1` по инструкции выше. Команды ниже оставлены только для переноса уже имеющихся файлов в целях отладки. В обычном режиме GigaAM теперь хранится в `models/gigaam-v3-e2e-rnnt/`, а не только в общем кэше Hugging Face.


Скопировать **только веса**, не скрипты и не датасеты:

```powershell
Copy-Item ..\404BNF\models\mobile_diarization\silero_vad_v6.2.1.onnx .\models\mobile_diarization\
Copy-Item '..\404BNF\models\mobile_diarization\voxceleb_CAM++.onnx' .\models\mobile_diarization\
```

Проверьте:

```powershell
Test-Path .\models\mobile_diarization\silero_vad_v6.2.1.onnx
Test-Path '.\models\mobile_diarization\voxceleb_CAM++.onnx'
```

Обе команды должны вывести `True`. ONNX-веса намеренно не вложены в ZIP. Если исходная папка весов у вас другая, исправьте путь в командах копирования.

**GigaAM:** `setup.ps1` использует локальную загрузку через `huggingface_hub.hf_hub_download(..., revision=<commit>, local_dir=...)` для загрузки FP32-весов, а `onnx-asr.load_model(..., path=...)` — для их локального запуска и сохраняет веса в папке проекта. Обычный `run.py` не должен начинать сетевую загрузку. Результаты оригинальных экспериментов/эталоны в этом проекте не используются.

## 3. Запуск одной командой

Пример: читаем WAV из **старого проекта**, но записываем итог **в новый**:

```powershell
.\.venv\Scripts\python.exe run.py --audio ..\404BNF\data\01_dialog_clean.wav --id 01_dialog_clean --output output\01_dialog_clean.json --status-file output\01_clean_status.json
```

Чтобы повторить запуск в тот же файл, добавить `--overwrite`.

Параметры:

- `--audio`: WAV/MP3/M4A/FLAC и прочие поддерживаемые форматы;
- `--id`: ID записи; если не указан — имя файла без расширения;
- `--output`: **JSON v1** (по умолчанию `output/<id>.json`);
- `--status-file`: отдельный JSON со стадией обработки;
- `--models-dir`: путь к Silero и CAM++ ONNX;
- `--cluster-distance`: порог кластеризации, **0.55** по умолчанию;
- `--num-speakers`: число говорящих только если пользователь заранее его знает;
- `--cancel-file`: создать этот файл, чтобы запросить отмену **между стадиями**;
- `--overwrite`: явно разрешить перезапись результата.

Пример с M4A:

```powershell
.\.venv\Scripts\python.exe run.py --audio "C:\path\to\recording.m4a" --output output\recording.json
```

**На вашем ноутбуке** предыдущее полное A6 на 01_dialog_clean дало 135 слов, 15 реплик, 2 говорящих и cpWER 10.2%. Чистая сборка создана из того же кода, но её **реальный запуск с весами** необходимо повторить в новом окружении — текущая проверка сборки выполнялась с подставными результатами моделей.

## 4. Структура

```text
404BNF-ML-Core/
├── run.py                           # единственная точка запуска
├── setup.ps1                        # однокомандная установка Windows
├── MODEL_SETUP.md                   # где и как получить веса
├── tools/model_setup.py             # проверка моделей и SHA256
├── requirements.txt                 # общие Python CPU зависимости
├── requirements-win-py313.lock      # версия-пиннинг для Windows 3.13
├── transcript_schema_v1.json        # контракт для Android
├── processor_core/
│   ├── __init__.py
│   ├── contracts.py                  # единые интерфейсы
│   ├── registry.py                   # подключение новых моделей
│   ├── model_assets.py               # источники и версии весов
│   ├── audio.py                      # SHA-256 оригинала
│   ├── preprocess.py                 # FFmpeg -> mono/16k/float32
│   ├── pipeline.py                   # слова + говорящие -> JSON
│   ├── processing.py                 # статусы, отмена, проверки, запись
│   └── engines/
│       ├── __init__.py
│       ├── gigaam.py                  # GigaAM ASR
│       ├── wespeaker.py               # DiarizationEngine
│       └── diarization_impl.py        # Silero, FBank, clustering
├── models/mobile_diarization/        # Silero и CAM++
├── models/gigaam-v3-e2e-rnnt/        # исходная GigaAM FP32 (устанавливается автоматически)
└── output/                           # только финальные результаты
```

**Отдельно в старом 404BNF оставить:** `tests/`, `score_*.py`, `results/` с метриками, датасеты `data/`, `manifest.jsonl`, `mobile_diarization.py`, ранние `run_a*.py`, скрипты бенчмарков, альтернативные модели и окружения `.venv*`.

## 5. Важные ограничения

- Это **Python-ядро для разработки и сверки**. Android-приложение будет запускать ONNX локально в Kotlin/Java через Android-совместимые адаптеры. Кода для запуска Python внутри APK нет.
- Мобильный инференс и воспроизводимость качества на vivo V25e **ещё не подтверждены**.
- Потоковая обработка непосредственно внутри моделей не реализована: полное аудио собирается в памяти, ограничение по умолчанию 30 минут.
- ONNX-сессии вызываются последовательно; отмена — только на границах стадий.
- Пересечения нескольких говорящих WeSpeaker на одной временной шкале не разделяет.
- Оценки времени слов GigaAM приблизительные (по токенам RNNT, не forced alignment).
- `schema_version=1`, `result_id`, `audio_sha256`, `segments[].id` сохранены, искусственных confidence нет.
- Сохранение результата атомарное; без `--overwrite` старый результат не заменяется.
- Модели имеют отдельные лицензии: проверьте условия их дальнейшего распространения перед публикацией приложения.
