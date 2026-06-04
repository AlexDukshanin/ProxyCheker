# ProxyCheker

Минималистичное Android-приложение для проверки MTProto-прокси Telegram.

`ProxyCheker` загружает открытые MTProto-прокси из GitHub-источников, проверяет их доступность, сортирует рабочие варианты по пингу и позволяет быстро открыть нужную ссылку в Telegram.

## Возможности

- Загрузка MTProto-прокси из открытых источников `RU`, `EU` и `World`
- Автоматическая фильтрация нерабочих прокси
- Сортировка рабочих прокси по задержке от меньшей к большей
- Минималистичный темный интерфейс на Jetpack Compose
- Быстрый переход по прокси-ссылке в Telegram
- Очистка локального кеша прямо из приложения

## Источники

Приложение использует открытые списки:

- `https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_ru.txt`
- `https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_eu.txt`
- `https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_all.txt`

Списки обновляются сторонним открытым источником примерно каждые 12 часов.

## Важно

- Приложение не поднимает собственные прокси и не управляет ими
- Надежность, безопасность и доступность прокси не гарантируются
- Создатель приложения не несет ответственности за содержимое и работоспособность сторонних списков

## Требования

- Android 7.0+ (`minSdk 24`)
- Установленный Telegram для открытия `tg://` ссылок

## Сборка

Сборка debug:

```bash
./gradlew assembleDebug
```

Сборка release:

```bash
./gradlew assembleRelease
```

Готовый APK после сборки:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

## Публикация

Для публичной раздачи рекомендуется подписать один universal APK и прикрепить его к GitHub Release.

## Стек

- Kotlin
- Jetpack Compose
- Material 3

