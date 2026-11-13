# Experimental SFU carrier

The adapter in `mobile.go` uses the Telemost/VK guest clients and VP8 frame transport
from [kulikov0/whitelist-bypass](https://github.com/kulikov0/whitelist-bypass), MIT,
pinned to `a26b5555728e5b3e990b8204f7663cc742c9de3b` (v0.5.1).
`UPSTREAM-LICENSE` retains the upstream notice. `build.sh` checks out that exact
commit in ignored `.build/carrier-upstream`, copies the adapter into its module,
runs the Go race tests, then builds an ARM/ARM64 gomobile AAR. No upstream binary
is shipped without rebuilding. The gomobile linker uses `-checklinkname=0`, as
required by the pinned anet Android interface-enumeration library on Go 1.23+. Dependency versions and checksums come from the
pinned upstream go.mod/go.sum; do not replace the pin with a branch or `latest`.

The upstream creates a tunnel to a separate internet gateway. DP Call instead
connects two guest participants directly at the tunnel layer. Each endpoint has a
loopback-only TURN server whose virtual relay sockets are carried in VP8 frames.
The app's original libwebrtc engine handles ICE, DTLS, SRTP, Opus, audio devices
and the control data channel. Nothing forwards packets to arbitrary internet
addresses. There is no VPN permission or remote gateway.

The pair key is HKDF-SHA256(ECDH(identity keys), SHA256(canonical room URL),
`dpcall-carrier-v1`). Distinct AES-256-GCM keys for each direction prevent self-view
reflection; random 96-bit nonces and a bounded 65,536 nonce replay cache protect
frames. The service's room-link-derived obfuscation is only an outer wrapper;
knowing the room link does not reveal the pair key. The media also retains its
independent end-to-end DTLS-SRTP encryption. Authenticated heartbeats distinguish
"joined SFU" from "peer reachable". The selected peer must be present; the room
itself is not treated as proof of peer connectivity.

Only the Experimental APK includes this module. Its application ID is
`com.dpsmpx.dpcall.experimental`, so its keys/contacts are separate. Public relays,
public ICE discovery, automatic update checks and ordinary TURN fallback are
not started in this APK. The user explicitly enters a room, a peer identity and
accepts the service disclosure each session. SFU connection failures never turn
into a direct call. Captcha UI is restricted to the in-process VK guest flow;
only loopback HTTP is allowed by the experimental network security config.

Local tests cover authenticated signals, malformed input, echo/replay rejection,
bidirectional TURN allocation/permissions/packets and a full DTLS/SCTP data channel
through the two virtual TURN servers. Those tests use an in-memory SFU substitute;
they do not establish that Telemost/VK currently relay two guest streams on a
particular mobile network. Live room and real Android audio tests remain required.

Requirements: Go 1.26.1, Android SDK, NDK 29.0.14206865, Java 17. GitHub Actions
builds the AAR before Gradle tests and all release APKs. For local adapter tests:

```sh
./carrier/build.sh test
```

The upstream transport's private SFU protocols may change. Update the pin only
after reviewing the diff and rerunning local tests plus real two-phone calls on
both carriers. No success claim about Russian whitelists follows from CI.
