# Project structure

```
DP_Call/
├── app/
│   └── src/main/java/com/dpcall/app/
│       ├── call/          # CallManager, CallService, TelecomBridge, notifications
│       ├── data/          # DataStore repositories
│       ├── domain/        # Identity, Contact
│       ├── signaling/     # DPCALL1 codec; relay/ — Nostr signaling
│       ├── webrtc/        # PeerConnection, audio processing and routing
│       ├── ui/            # Compose screens
│       └── util/
│   └── src/test/          # модульные и живые (DPCALL_LIVE_RELAYS=1) тесты
├── docs/
│   ├── USER_GUIDE.md      # руководство пользователя
│   ├── SECURITY.md        # модель угроз, известные ограничения
│   ├── PRIVACY.md         # политика конфиденциальности (RU/EN)
│   ├── TZ.md
│   ├── ARCHITECTURE.md
│   ├── RELEASING.md       # выпуск версий, подпись
│   ├── FDROID.md          # публикация в F-Droid
│   ├── fdroid/            # готовый файл метаданных для fdroiddata
│   ├── design/
│   └── adr/
├── fastlane/metadata/     # описание, иконка, changelog для F-Droid
├── .github/workflows/     # сборка и публикация релизов
├── README.md
└── LICENSE
```
