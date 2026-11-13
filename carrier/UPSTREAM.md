# Optional service carrier and Tor native module

The adapter in `mobile.go` uses the Telemost/VK guest clients and VP8 frame transport
from [kulikov0/whitelist-bypass](https://github.com/kulikov0/whitelist-bypass), MIT,
pinned to `a26b5555728e5b3e990b8204f7663cc742c9de3b` (v0.5.1).
`UPSTREAM-LICENSE` retains the upstream notice. `build.sh` checks out that exact
commit in ignored `.build/carrier-upstream`, copies the adapter into its module,
runs the Go race tests, then builds an ARM/ARM64 gomobile AAR. No upstream binary
is shipped without rebuilding. The gomobile linker uses `-checklinkname=0`, as
required by the pinned anet Android interface-enumeration library on Go 1.23+. Dependency versions and checksums come from the
pinned upstream go.mod/go.sum; do not replace the pin with a branch or `latest`.

The upstream creates a tunnel to a separate internet gateway. `upstream.patch`
adds the guest initiator role for VK, forces VK media through its own TURN
servers, verifies TLS certificates, and fixes IPv6 address joining. Telemost
uses its SFU; VK uses an authenticated two-participant call via the service TURN. DP Call instead
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
independent end-to-end DTLS-SRTP encryption. Fresh random challenge/response heartbeats distinguish
"joined SFU" from "peer reachable". Data frames are bound to the receiver’s fresh session challenge,
so an old session’s recorded frames cannot be reused after reconnect. The selected peer must be present; the room
itself is not treated as proof of peer connectivity.

Both standard and Tor APKs include the optional carrier. It is off by default,
uses the existing app identity and contacts, and does not persist room selection.
Public relays, public ICE discovery, automatic update checks and ordinary TURN
fallback are paused while selected. Leaving restores normal signaling and the
previous Tor preference. Tor does not carry the room connection: the service sees
the device IP. Failures stay in room mode. Captcha UI is restricted to the
in-process VK guest flow; only loopback HTTP is permitted by network security config.

Tor also binds [IPtProxy 5.7.0](https://github.com/tladesignz/IPtProxy/tree/46552b8422a200e3e69aa3686b6278be945138a5),
pinned to `46552b8422a200e3e69aa3686b6278be945138a5`, with its DNSTT submodule
`f1b9b97a269f83bad41d2ceef291b4d2c161cd11`. `tor.mod`/`tor.sum` select the shared
dependency graph. The standard APK has no IPtProxy dependency. The Tor APK binds
both Go packages into ONE AAR/runtime (`libgojni.so`, `go.Seq`), avoiding duplicate
Java classes and a native-library collision. Tests rerun the carrier with both
graphs and start/stop an actual loopback obfs4 listener. Neither starts a bridge
connection on the internet during these tests.

Our adapter and the guest client are MIT licensed. IPtProxy's wrapper is MIT;
its dependencies include GPL-3.0 Lyrebird and BSD Snowflake. The distributed Tor
binary therefore includes components under additional licenses. License notices
are generated into each flavor's `assets/carrier-licenses.txt`. GitHub releases
include `DP_Call-native-sources.tar.gz` with patched guest sources, adapter,
pinned IPtProxy/DNSTT sources, Go module checksums and vendored Tor dependencies.
The root project's MIT notice describes our own code, not exclusive licensing
of every third-party binary. Service API permission is separate from source licensing.

Local tests cover authenticated signals, malformed input, echo/replay rejection,
bidirectional TURN allocation/permissions/packets and a full DTLS/SCTP data channel
through the two virtual TURN servers. A patched upstream test also verifies
that the chosen VK initiator offers only after the PC and remote participant exist. Those tests use an in-memory SFU substitute;
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

## Rebuilding the source archive

Use Go 1.26.1, Java 17, Android SDK and NDK 29.0.14206865. Install the pinned
`gomobile` and `gobind` version `v0.0.0-20260908204917-8b95e45f8d3e` and put
GOPATH/bin on PATH. In the archive's `carrier-upstream` directory, set
ANDROID_NDK_HOME and `CGO_LDFLAGS=-Wl,-z,max-page-size=16384`, then run:

```sh
export GOFLAGS="-mod=vendor -modfile=$PWD/tor.mod"
gomobile bind -trimpath -ldflags="-s -w -checklinkname=0" \
  -target=android/arm,android/arm64 -androidapi 26 -javapkg org.dpcall \
  -o mobile-tor.aar ./dpcallmobile github.com/tladesignz/IPtProxy.git
```

The standard graph remains in go.mod/go.sum; unset GOFLAGS, fetch its pinned
modules with `-mod=readonly` and bind only ./dpcallmobile to rebuild mobile.aar.
The full Android app and build driver are in the matching DP Call Git tag.
