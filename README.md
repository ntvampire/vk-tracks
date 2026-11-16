# 📦 VK Tracks

[![Релиз](https://img.shields.io/badge/Релиз-v1.0.54%20(Stable)-FA2D48?style=flat-square)](https://github.com/ntvampire/vk-tracks/releases/latest)
[![Статус](https://img.shields.io/badge/Статус-Активен-brightgreen?style=flat-square)](https://github.com/ntvampire/vk-tracks)
[![Платформа](https://img.shields.io/badge/Платформа-Android%208.0%2B-0078D6?style=flat-square)](https://developer.android.com)
[![Стек](https://img.shields.io/badge/Стек-Kotlin%20%7C%20Compose%20%7C%20Media3-7F52FF?style=flat-square)](https://github.com/ntvampire/vk-tracks)

Легковесный, высокопроизводительный аудиоплеер для Android с интеграцией VK Музыки, поддержкой радиостанций и эстетикой Apple Music.

---

## 🎯 Назначение

VK Tracks решает задачу удобного, быстрого и автономного прослушивания музыки из социальной сети ВКонтакте без рекламы, ограничений фонового режима и визуального шума:
* **Интерфейс:** Лаконичный дизайн в стиле Apple Music (Material 3 + кастомная стилизация, плавная бегущая строка Marquee, плавающий MiniPlayer и полноэкранный FullPlayerSheet с караоке-текстами).
* **Среда выполнения:** Android 8.0+ (API 26+) для смартфонов, планшетов и ТВ/автомобильных медиасистем.
* **Автономность:** Прямое воспроизведение MP3 и HLS (.m3u8), кэширование сетевых потоков на лету (ExoPlayer SimpleCache) и загрузчик аудио с вшиванием ID3v2-тегов и обложек в системную медиатеку `Music/VK Tracks/`.

| Ресурс | Ссылка |
| :--- | :--- |
| **Прямое скачивание APK** | [VK-Tracks-1.0.54.apk](https://ntvampire.github.io/vk-tracks/VK-Tracks-1.0.54.apk) |
| **Манифест автообновления** | `https://ntvampire.github.io/vk-tracks/update.json` |

---

## ✨ Особенности

* **Надежный аудиодвижок Media3:** Фоновая служба `MediaSessionService`, полная интеграция с System Media Controls (шторка уведомлений и экран блокировки) с передачей обложек высокого разрешения, умными действиями (лайки/сердечки) и аудиофокусом.
* **Каскадный резолвер обложек:** Нативные обложки VK API $\rightarrow$ In-Memory кэш $\rightarrow$ база данных Room $\rightarrow$ высокоскоростной API Яндекс Музыки (основной студийный источник 600×600 в РФ) $\rightarrow$ Apple Music/iTunes API (резервный контур). Защита Generic Guard исключает ложный поиск для безымянных треков.
* **Прямой эфир радио:** Радио «Studio 21» (WebSocket EMG + российские метасерверы) и «Record» (JSON API) со стабилизацией потоков без скрипов и аудио-артефактов.
* **Умная пагинация и 120 FPS:** Мгновенный отклик интерфейса, изолированные обновления состояний плеера, отсутствие лишних перерисовок и фрагментации памяти JVM (`android:largeHeap="true"`).
* **Синхронизация с серверами:** Избранное, персональная волна «VK Mix» с настройками, альбомы, плейлисты, поиск по исполнителям и трекам, синхронизация лайков.
* **Встроенный OTA-апдейтер:** Автономная система проверки и фоновой установки обновлений по воздуху через GitHub Pages CDN без сервисов Google Play.

---

## 🤝 Благодарности

* [AndroidX Media3](https://developer.android.com/media/media3) — за эталонную архитектуру воспроизведения мультимедиа и медиасессий.
* [Coil](https://coil-kt.github.io/coil/) — за легковесную и быструю асинхронную загрузку изображений в Jetpack Compose.
* [VK Music / BOOM](https://vk.com) — за официальный API доступа к аудиокаталогу.
* [Яндекс Музыка](https://music.yandex.ru) — за высокоскоростной каталог студийных обложек релизов в РФ.
* [Studio 21](https://studio21.ru) и [Radio Record](https://www.radiorecord.ru) — за открытые эфирные потоки качественной радиовещательной музыки.
