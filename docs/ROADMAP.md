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
- Store listing metadata (descriptions, screenshots)

## Stage 3.1 — v1.1.0 (done)
- R8 minification, ARM-only release APK
- ICE restart on network change
- QR codes, call history, unknown-caller prompt
- Custom TURN server, configurable relay list, local error log export
- English UI, privacy policy, reproducible build

## Stage 3.2 — v1.2.0 (done)
- Readiness banner: green only when a call will really get through
- Missed-call notes for calls made while the callee was offline (stored on relays for 48 h)
- Password-protected backup of the key and contacts
- Update notice from GitHub Releases
- Contact codes accepted from the share sheet and the clipboard; call back from the
  missed-call notification; add unknown callers from the history

## Stage 3.3 — v1.2.1 (done)
- Invite button: a direct download link for people who do not have the app yet
- Permanent `latest/download/DP_Call.apk` link

## Stage 3.4 — v1.3.0 (done)
- Relay health with benching and spare relays; front-loaded resends; path check (STUN/TURN)
  with a connection-check dialog
- Adaptive Opus bitrate, softer receive noise gate, "how well you hear" indicator
- Scan → Call / Save card, code extraction from messy messages
- Vendor-skin setup (autostart) and opt-in Always connected mode
- Tor build: signaling through embedded Tor, released as a second APK

## Stage 4 — Hardening
- Security and code audit before the public release (done, see SECURITY.md)
- Own release signing key (done)
- Stress tests on real networks, Doze and vendor battery savers

## Later
- Optional relay-only ICE (hide the IP address from the other person, at the cost of TURN
  dependence)
