# Project structure

```
DP_Call/
├── app/
│   └── src/main/java/com/dpsmpx/dpcall/
│       ├── call/          # CallManager, CallService, TelecomBridge, notifications
│       ├── data/          # DataStore repositories, settings, backup
│       ├── domain/        # Identity, Contact
│       ├── signaling/     # DPCALL1 codec; relay/ — Nostr signaling
│       ├── webrtc/        # PeerConnection, audio processing and routing
│       ├── ui/            # Compose screens
│       ├── update/        # проверка новой версии в GitHub Releases
│       └── util/
│   └── src/test/          # модульные и живые (DPCALL_LIVE_RELAYS=1) тесты
├── docs/
│   ├── USER_GUIDE.md      # руководство пользователя
│   ├── SECURITY.md        # модель угроз, известные ограничения
│   ├── PRIVACY.md         # политика конфиденциальности (RU/EN)
│   ├── TZ.md
│   ├── ARCHITECTURE.md
│   ├── RELEASING.md       # выпуск версий, подпись
│   ├── FDROID.md, fdroid/ # архив: публикация в каталоге отменена
│   ├── design/
│   └── adr/
├── fastlane/metadata/     # описание, иконка, скриншоты, тексты выпусков
├── .github/workflows/     # сборка и публикация релизов
├── README.md
└── LICENSE
```
