# Architecture DP_Call

## Overview

Pure P2P voice calls over WebRTC. No servers of our own. Identity is a secp256k1 key pair; the
shareable code is the public key (Crockford Base32). SDP Offer/Answer (DPCALL1 format) travel
end-to-end encrypted through public Nostr relays (see ADR-002); manual exchange through any
messenger remains as a fallback.

## Layers

```
ui/          Compose screens (Home, Call), MainActivity
call/        CallManager (process-wide call engine), CallService (foreground service +
             notifications), TelecomBridge (system Telecom), RelaySignaling + ListenService
             (background reachability), share target, notification actions
domain/      Identity, Contact
data/        DataStore repositories
signaling/   DPCALL1 encode/decode/extract
signaling/relay/  Nostr events, envelope encryption, relay pool (Android-free, JVM-testable)
webrtc/      PeerConnectionManager, audio processing, audio routing
```

`CallManager` is a singleton that outlives the Activity. UI, the notification and the headset
all drive the same object and observe one `StateFlow<CallUiState>`.

```
MainActivity / CallScreen ─┐
CallService (notification) ─┼─▶ CallManager ─▶ PeerConnectionManager (WebRTC)
TelecomBridge (headset)   ─┘         │
SignalReceiverActivity (share) ──────┤
RelaySignaling (Nostr relays) ───────┘
```

## Call flow

1. A creates Offer → `offer` message to B through the relays (resent until acknowledged)
2. B's `ListenService` keeps relay connections open → state RINGING: CallStyle notification
   with Accept / Decline, full-screen intent on a locked device; B replies `ringing`
3. B accepts → Answer is created → `answer` message back to A
4. A applies the Answer → ICE → CONNECTED

`reject`, `busy`, `cancel` and `bye` cover the other outcomes. If B is unreachable, A can still
send the DPCALL1 string manually (share sheet, clipboard pickup or paste).

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
