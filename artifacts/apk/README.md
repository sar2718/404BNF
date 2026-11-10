# APK для демонстрации

**404BNF 1.0 — debug APK** — опубликованная сборка от 8 ноября 2026, 157938508 байт (150.6 МиБ). Это отладочная сборка; установка и работа на физическом устройстве при подготовке этого APK не проверялись.

Исходный коммит: `a8d21abad0877368e6bf59f15ea968938f978a55`. Паспорт и SHA-256 относятся к этому APK; сборка текущего рабочего дерева создаёт отдельный артефакт. Подпись опубликованного APK проверена `apksigner verify`, сведения манифеста — `aapt dump badging`. Веса моделей не включены.

[SHA-256](SHA256SUMS) · [Метаданные](manifest.json) · [Установка](../../docs/INSTALLATION.md)

Проверка из этой директории:

```bash
sha256sum -c SHA256SUMS
```

## Получение APK из GitHub Releases

[404BNF 1.0 — Hackathon Debug](https://github.com/sar2718/404BNF/releases/tag/v1.0.0-debug) опубликован с отметкой prerelease. Вложения доступны без авторизации:

- [404BNF-1.0-debug.apk](https://github.com/sar2718/404BNF/releases/download/v1.0.0-debug/404BNF-1.0-debug.apk) — установочный пакет;
- [SHA256SUMS](https://github.com/sar2718/404BNF/releases/download/v1.0.0-debug/SHA256SUMS) — контрольная сумма;
- [manifest.json](https://github.com/sar2718/404BNF/releases/download/v1.0.0-debug/manifest.json) — паспорт сборки.

8 ноября 2026 проверено: релиз опубликован, все три вложения загружены, их серверные SHA-256 совпадают с локальными файлами. Доступ к APK без авторизации подтверждён ответом HTTP 200.

Скачайте APK и SHA256SUMS в одну папку, затем выполните `sha256sum -c SHA256SUMS`. Для установки следуйте [инструкции](../../docs/INSTALLATION.md).

## Хранение в репозитории

Файл больше 100 МиБ: он распространяется вложением Release, а не обычным Git blob — [правила GitHub](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github). Локальная копия `artifacts/apk/404BNF-1.0-debug.apk` исключена через `.gitignore`; паспорт и контрольная сумма хранятся в Git. При клонировании скачайте APK из опубликованного релиза либо соберите его из исходников. Настройка Git LFS для этого способа передачи не требуется.
