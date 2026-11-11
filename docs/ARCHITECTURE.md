# Architecture DP_Call

## Overview

Pure P2P voice calls over WebRTC. No signaling server. Identity is a local Crockford Base32 code. SDP Offer/Answer exchanged manually via clipboard (DPCALL1 format).

## Layers

```
ui/          Compose screens (Home, Call)
domain/      Identity, Contact
data/        DataStore repositories
signaling/   DPCALL1 encode/decode
webrtc/      PeerConnectionManager, CallController
```

## Call flow

1. A creates Offer → copies DPCALL1.O.* string
2. A sends string to B (messenger)
3. B pastes Offer → creates Answer → copies DPCALL1.A.*
4. B sends Answer to A
5. A applies Answer → ICE → CONNECTED

## ICE

- STUN: Google public
- TURN: optional public openrelay (no self-hosted servers)

## Constraints

- Android only
- Voice only
- Open source (MIT)
- Distribution: GitHub Releases + F-Droid
