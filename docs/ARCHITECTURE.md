# Architecture DP_Call

## Overview

Pure P2P voice calls over WebRTC. No signaling server. Identity is a local Crockford Base32 code.
SDP Offer/Answer are exchanged manually (DPCALL1 format) through any messenger: share sheet,
clipboard pickup or manual paste.

## Layers

```
ui/          Compose screens (Home, Call), MainActivity
call/        CallManager (process-wide call engine), CallService (foreground service +
             notifications), TelecomBridge (system Telecom), share target, notification actions
domain/      Identity, Contact
data/        DataStore repositories
signaling/   DPCALL1 encode/decode/extract
webrtc/      PeerConnectionManager, audio processing, audio routing
```

`CallManager` is a singleton that outlives the Activity. UI, the notification and the headset
all drive the same object and observe one `StateFlow<CallUiState>`.

```
MainActivity / CallScreen ─┐
CallService (notification) ─┼─▶ CallManager ─▶ PeerConnectionManager (WebRTC)
TelecomBridge (headset)   ─┘         │
SignalReceiverActivity (share) ──────┘
```

## Call flow

1. A creates Offer → shares the DPCALL1.O.* string
2. B receives it (Share → DP Call, clipboard, or paste) → state RINGING:
   CallStyle notification with Accept / Decline, full-screen intent on a locked device
3. B accepts → Answer is created → B shares DPCALL1.A.* back
4. A applies the Answer (auto-picked from clipboard / share) → ICE → CONNECTED

There is no push channel: an incoming call can only start when the Offer reaches the device
through the user. A future signaling transport would plug into `CallManager.handleSignal`.

## Background and system integration

- `CallService` — foreground service (`phoneCall|microphone`), keeps the call alive when the app
  is minimized, shows the ongoing-call notification (Hang up, Mute), holds CPU and proximity
  wake locks.
- `TelecomBridge` — registers the call as self-managed via Jetpack Core-Telecom: wired and
  Bluetooth headset buttons (answer / hang up), audio endpoints (earpiece, speaker, wired,
  Bluetooth), hold when a cellular call arrives. If Telecom is unavailable the call falls back
  to `AudioCallManager` routing and a `MediaSession` for the headset button.
- A negotiated data channel (`id=0`) carries `bye` (instant hang-up on both sides) and the
  remote mute state.

## Audio quality

- Capture: WebRTC Audio Processing — echo cancellation (hardware when available), noise
  suppression, automatic gain control, high-pass filter.
- Playback: `ReceiveNoiseGate` — a downward expander on the received audio
  (render pre-processing), attenuates background noise in pauses; never amplifies.
- Opus: 48 kHz full band, 48 kbit/s, in-band FEC, DTX off (`SdpTuning`, applied to the remote
  description, so no renegotiation is involved).
- Noise suppression can be toggled during a call (both directions).

## ICE

- STUN: Google public
- TURN: optional public openrelay (no self-hosted servers)

## Constraints

- Android only
- Voice only
- Open source (MIT)
- Distribution: GitHub Releases + F-Droid
