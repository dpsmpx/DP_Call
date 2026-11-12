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

## Stage 2.5 — Real dialer behaviour (done)
- Incoming call notification with Accept / Decline
- Foreground service: calls survive minimizing, hang up from the notification
- System Telecom integration: wired / Bluetooth headset buttons, audio endpoints, hold
- Mute, noise suppression both ways, Opus tuning
- UI polish

## Stage 2.6 — Calls reach the peer by themselves (done)
- Key-based identity (code = public key)
- End-to-end encrypted signaling over public Nostr relays
- Background listening service, start on boot
- Contacts-only incoming calls

## Stage 3 — First release (done, v1.0.0)
- First-run guide, permission UX
- GitHub Actions build, GitHub Releases
- F-Droid metadata and submission guide

## Stage 3.1 — v1.1.0 (implemented, device testing pending)
- R8 minification, ARM-only release APK
- ICE restart on network change
- QR codes, call history, unknown-caller prompt
- Custom TURN server, configurable relay list, local error log export
- English UI, privacy policy, reproducible build

## Stage 4 — Hardening
- Security and code audit before the public release (done, see SECURITY.md)
- Own release signing key
- F-Droid inclusion; app screenshots for the store listing
- Stress tests on real networks, Doze and vendor battery savers

## Later
- Optional Tor transport for signaling (maximum privacy mode)
