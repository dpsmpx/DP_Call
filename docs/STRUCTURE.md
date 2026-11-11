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
├── docs/
│   ├── TZ.md
│   ├── ARCHITECTURE.md
│   ├── design/
│   └── adr/
├── README.md
└── LICENSE
```
