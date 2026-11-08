# APK для демонстрации

**404BNF 1.0 — debug APK** — результат актуального `assembleDebug`, 157938508 байт (150.6 МиБ). Это отладочная сборка; установка и работа на физическом устройстве при подготовке не проверялись.

Исходный коммит: `a8d21abad0877368e6bf59f15ea968938f978a55`. После сборки изменялись только документация и правила игнорирования. Подпись APK проверена `apksigner verify`, сведения манифеста — `aapt dump badging`. Веса моделей не включены.

[SHA-256](SHA256SUMS) · [Метаданные](manifest.json) · [Установка](../../docs/INSTALLATION.md)

Проверка из этой директории:

```bash
sha256sum -c SHA256SUMS
```

## Получение APK из GitHub Releases

[Открыть релизы 404BNF](https://github.com/sar2718/404BNF/releases). Ищите `v1.0.0-debug` с названием **404BNF 1.0 — Hackathon Debug** и отметкой prerelease. Вложения:

- `404BNF-1.0-debug.apk` — установочный пакет;
- `SHA256SUMS` — контрольная сумма;
- `manifest.json` — паспорт сборки.

На момент проверки 8 ноября 2026 все три файла загружены в черновик релиза; их серверные SHA-256 совпадают с локальными файлами. Черновик необходимо опубликовать, чтобы вложения стали доступны экспертам. Само открытие страницы через `gh release view --web` не публикует релиз.

После публикации постоянные ссылки будут доступны по адресам:

```text
https://github.com/sar2718/404BNF/releases/download/v1.0.0-debug/404BNF-1.0-debug.apk
https://github.com/sar2718/404BNF/releases/download/v1.0.0-debug/SHA256SUMS
https://github.com/sar2718/404BNF/releases/download/v1.0.0-debug/manifest.json
```

Скачайте APK и SHA256SUMS в одну папку, затем выполните `sha256sum -c SHA256SUMS`. Для установки следуйте [инструкции](../../docs/INSTALLATION.md).

## Хранение в репозитории

Файл больше 100 МиБ: он распространяется вложением Release, а не обычным Git blob — [правила GitHub](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github). Локальная копия `artifacts/apk/404BNF-1.0-debug.apk` исключена через `.gitignore`; паспорт и контрольная сумма хранятся в Git. При клонировании скачайте APK из опубликованного релиза либо соберите его из исходников. Настройка Git LFS для этого способа передачи не требуется.
