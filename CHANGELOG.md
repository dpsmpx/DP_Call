# Changelog

Все значимые изменения проекта документируются в этом файле.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.0.0/).

## [Unreleased]

### Added
- Генерация криптостойкого identity (16 байт → Base32 Crockford, 26 символов)
- Локальное хранение identity через DataStore
- Экран «Ваш код» с кнопкой копирования в буфер обмена
- Каркас Android-проекта (Compose + Material 3)
- Формат SDP-строки (DPCALL1) и ADR-001
- Полная документация проекта (TZ, Architecture, Roadmap, Design)

### Notes
- WebRTC и логика звонков пока не реализованы

### Added
- Добавление контактов по коду (валидация, имя, хранение)
- Список контактов на главном экране
- Диалог добавления с вставкой из буфера
- Удаление контакта

### Added
- Модуль signaling: кодирование/декодирование DPCALL1-строк
- SdpPayload, SignalCodec, SignalingHelper
- Сжатие gzip + Base64URL, защита от пробелов/переносов

### Added
- WebRTC каркас (stream-webrtc-android 1.3.10)
- PeerConnectionManager (только голос)
- CallController + CallState machine
- Экран звонка с ручным обменом Offer/Answer
- Публичные STUN + опциональный TURN (openrelay)
- Runtime-запрос RECORD_AUDIO
