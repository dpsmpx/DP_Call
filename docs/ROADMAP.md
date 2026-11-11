# Roadmap

## Stage 0 — Documentation (done)
- TZ, Architecture, Design, SDP format

## Stage 1 — MVP skeleton (done)
- Identity generation + local storage
- Contacts add/list/remove
- Signaling module (DPCALL1)
- WebRTC PeerConnectionManager (audio-only)
- Call flow with manual Offer/Answer exchange
- Call UI

## Stage 2 — Call reliability (done)
- Correct ICE candidate mid/m-line handling
- Audio focus + speakerphone
- Better error messages
- Connection quality indicators

## Stage 2.5 — Real dialer behaviour (done, needs device testing)
- Incoming call notification with Accept / Decline
- Foreground service: calls survive minimizing, hang up from the notification
- System Telecom integration: wired / Bluetooth headset buttons, audio endpoints, hold
- Mute, noise suppression both ways, Opus tuning
- UI polish

## Stage 3 — Polish
- Themes refinement
- Permission UX
- F-Droid metadata
- First GitHub Release

## Stage 4 — Hardening
- Stress tests on real networks + VPN
- Optional TURN toggle in settings
- Crash-free background behaviour
