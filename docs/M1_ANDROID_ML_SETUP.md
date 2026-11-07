# 404BNF — M1: Android ModelManager (2026-11-07)

## Что это

Реальное обновление существующего проекта **`src/android`** из архива команды.
Новый самостоятельный Android Gradle library-модуль `:ml` + **debug-only** диагностический экран в существующем `:app`.
**Никакого FastAPI, Python-интерпретатора на телефоне или INT8.**

Реализовано:

- ONNX Runtime **Android 1.31.1**, Maven Central (сессии открываются локально на CPU).
- ModelManager загружает GigaAM **исходную FP32** (encoder/decoder/joint + 3 конфигурационных файла), Silero VAD и WeSpeaker CAM++.
- ONNX-файлы сверяются с фиксированными SHA-256 из `src/ml-core/processor_core/model_assets.py`.
- GigaAM загружается по закреплённому коммиту `322c3b29492673eb7d0b434bfa9dfb8653e34d02`.
- Три маленьких конфигурационных файла GigaAM приходят из закреплённого коммита по HTTPS; пока проверяется **размер**, не отдельный SHA-256.
- Файлы лежат в `context.filesDir/models/` (перезагрузки и очистка кэша не удаляют их).
- Загрузка только по явному действию, фоновая передача после закрытия экрана **не реализована**; незавершённый `.part` возобновляется по HTTP Range, если сервер поддерживает.
- Модель публикуется в финальный путь только после проверки целостности (`.part` → rename).
- Проверка целостности на диске в `Dispatchers.IO`, контроль размера и доступного места, удаление повреждённых файлов **только вручную**.
- API `OnnxSessionManager.withSession()` последовательно открывает и закрывает сессии, избегая одновременного удержания GigaAM, WeSpeaker и Silero в RAM.
- Backup excludes `filesDir/models`.

**Пока не реализовано**: запуск графов с tensor inputs, MFCC/FBank, RNNT decoding, кластеризация, полная обработка аудио Android, реальный бенчмарк RAM, Android-сетевая загрузка в WorkManager/ForegroundService. **Успешное открытие ONNX-сессии ещё не доказывает правильный инференс.**

## Установка патча

1. В корне репозитория `404BNF` создайте отдельную ветку, чтобы не мешать Эдику:
   ```powershell
   git switch -c feat/android-ml-m1
   ```
2. Распакуйте содержимое `404BNF_M1_Android_ModelManager_PATCH.zip` **в корень репозитория** (уровень `src/` и `docs/`). Файлы `src/android/app/src/main/java/...` и экраны Эдика не изменяются. Патч изменяет несколько общих Gradle/manifest/backup XML; перед коммитом проверьте diff и согласуйте с Эдиком.
3. Откройте `src/android/` в Android Studio → Gradle Sync.
4. Debug APK:
   ```powershell
   cd src\android
   .\gradlew.bat :app:assembleDebug
   .\gradlew.bat :ml:testDebugUnitTest
   ```
5. Установите debug APK на телефон. В списке приложений должен появиться **дополнительный ярлык** `404BNF ML Setup (debug)`; основной интерфейс 404BNF останется прежним. Ярлык **отсутствует** в release.

## Проверка на телефоне

1. Откройте `404BNF ML Setup (debug)`.
2. Нажмите **1. Проверить файлы** — до установки увидите список отсутствующих.
3. Подключите Wi-Fi и нажмите **2. Скачать модели**. Нужны примерно 1–2 ГБ свободного места плюс пространство для приложения/системы. Первоначальная загрузка крупная; рекомендуется не закрывать экран. **Данные аудио никуда не отправляются.**
4. Дождитесь завершения; нажмите **1** снова.
5. Нажмите **3. Открыть ONNX-сессию Silero**. Это первая проверка ONNX Runtime непосредственно в APK.
6. Кнопка **4** пытается открыть все ONNX-графы **по очереди**, включая тяжёлый GigaAM FP32. Может занять много времени или вызвать OOM на устройстве: запускайте только осознанно. Эта кнопка НЕ запускает распознавание речи.
7. Отключите Wi-Fi. Проверка файлов и кнопка 3 должны продолжать работать. При желании откройте модели снова.

## Слои и API

```kotlin
val modelManager = ModelManager(context)
// Вызывать только по действию пользователя, из coroutine:
modelManager.ensureInstalled { progress -> /* фоновой callback, на UI переводить в Main */ }
val problems = modelManager.verifyInstalled()   // пусто == подготовлено

val onnx = OnnxSessionManager(modelManager)
val silero = ModelCatalog.onnxFilesFor(ModelId.SILERO_VAD).single()
onnx.withSession(silero) { session ->
    // Здесь позже будет OrtSession.run(inputs); сессия закроется автоматически.
}
```

Сетевые запросы выполняются **только** при `ensureInstalled()`. `verifyInstalled()`, `checkedFile()` и `withSession()` не скачивают ничего.

## Совместная работа и Git

- Не коммитьте `.onnx`, `.venv`, `*.part`, пользовательские записи, `build/` и `.gradle/`.
- Системное разрешение `INTERNET` добавлено в **main manifest** только для однократной загрузки моделей. Обработка аудио останется полностью локальной.
- Код `src/ml-core/` остаётся эталоном на Python; Android-модуль не импортирует Python.
- Не копируйте ранее подготовленный A7.3 Kotlin поверх уже существующих парсеров Эдика.
- Следующий этап M2 — реализовать GigaAM inference (FBank/RNNT) и Silero/WeSpeaker (VAD, FBank, embeddings), затем подключить результат к `TranscriptRepository.save()`.

## Проверки при подготовке патча

- Kotlin M1 sources проверены компиляцией `kotlinc` со **стабами** Android Context и ONNX Runtime API и установленным coroutines.jar; это **не** сборка APK.
- Хеши пяти ONNX-файлов и имена сравнивались с Python-эталоном.
- Полная Gradle Sync/APK сборка и загрузка крупной модели на Android **в этой среде не выполнялись**: нужен Android SDK/Android Studio и телефон. Первым действием после интеграции — выполнить шаги раздела выше.
