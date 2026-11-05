# VK Tracks

[![Latest Release](https://img.shields.io/github/v/release/ntvampire/vk-tracks?color=FA2D48&label=Release)](https://github.com/ntvampire/vk-tracks/releases/latest)
[![Status](https://img.shields.io/badge/Status-Active-brightgreen.svg)](https://github.com/ntvampire/vk-tracks/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android-blue.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/API-26%2B%20(Android%208.0%2B)-orange.svg)](https://developer.android.com)

Минималистичный мобильный плеер для Android с интеграцией VK Музыки, оформленный в эстетике Apple Music.

## Возможности
* **UI Apple Music:** Лаконичный дизайн, размытие обложек, всплывающий мини-плеер и полноэкранный плеер с караоке-текстами.
* **Авторизация:** Вход через официальный VK OAuth (поддержка 2FA, SMS, VK ID, без ввода пароля в код приложения).
* **Медиатека и каталог:** Личные аудиозаписи, плейлисты, подборки, новинки, чарты и поиск.
* **Радио:** Персональное радио «VK Mix» (поток рекомендаций).
* **Синхронизированные тексты (Lyrics):** Подсветка текущей строки по таймкодам с перемоткой по нажатию на строку.
* **Скачивание треков:** Загрузка прямого потока или HLS чанков в MP3 с прошивкой ID3v2-тегов (название, артист, альбом, обложка).
* **Умные обложки:** Каскадный поиск недостающих обложек (iTunes Search API + Deezer) с очисткой названий от шума и проверкой сходства.
* **Автообновление:** Проверка новых версий из GitHub репозитория по ссылке `https://ntvampire.github.io/vk-tracks/update.json`.
* **Фоновый звук:** Нативный AndroidX Media3 (ExoPlayer + MediaSession), управление со шторки и экрана блокировки.
* **Эквалайзер:** Вызов системного эквалайзера устройства.
