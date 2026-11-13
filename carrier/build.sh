#!/usr/bin/env bash
set -euo pipefail
project=$(cd "$(dirname "$0")/.." && pwd)
upstream="$project/.build/carrier-upstream"
revision=a26b5555728e5b3e990b8204f7663cc742c9de3b
if [ ! -d "$upstream/.git" ]; then
  mkdir -p "$(dirname "$upstream")"
  git clone --filter=blob:none --no-checkout https://github.com/kulikov0/whitelist-bypass.git "$upstream"
fi
git -C "$upstream" checkout --force --detach "$revision"
[ "$(git -C "$upstream" rev-parse HEAD)" = "$revision" ]
git -C "$upstream" apply "$project/carrier/upstream.patch"
cp "$project/carrier/vk_initiator_test.go.in" "$upstream/relay/pion/headless-joiner-common/dpcall_initiator_test.go"
mkdir -p "$upstream/dpcallmobile"
cp "$project"/carrier/*.go "$upstream/dpcallmobile/"
cd "$upstream"
# Previous source bundles may leave a vendor tree for the Tor graph.
rm -rf "$upstream/vendor"
go test -race ./dpcallmobile
go test -race ./relay/pion/headless-joiner-common -run '^TestDPCall'
# Tor binds IPtProxy and the carrier in ONE Go runtime, not two libgojni.so files.
ipt="$project/.build/iptproxy-source"
ipt_revision=46552b8422a200e3e69aa3686b6278be945138a5
if [ ! -d "$ipt/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/tladesignz/IPtProxy.git "$ipt"
fi
git -C "$ipt" checkout --force --detach "$ipt_revision"
[ "$(git -C "$ipt" rev-parse HEAD)" = "$ipt_revision" ]
git -C "$ipt" submodule update --init --recursive
[ "$(git -C "$ipt/dnstt" rev-parse HEAD)" = "f1b9b97a269f83bad41d2ceef291b4d2c161cd11" ]
cp "$ipt/LICENSE" "$ipt/IPtProxy.go/LICENSE"
cp "$project/carrier/tor_test.go.in" "$ipt/IPtProxy.go/dpcall_test.go"
cp "$project/carrier/tor.mod" "$upstream/tor.mod"
cp "$project/carrier/tor.sum" "$upstream/tor.sum"
# Test the room TURN/DTLS path again with Tor's selected dependency versions.
go test -mod=readonly -modfile=tor.mod -race ./dpcallmobile
go test -mod=readonly -modfile=tor.mod -race ./relay/pion/headless-joiner-common -run '^TestDPCall'
go test -mod=readonly -modfile=tor.mod -race github.com/tladesignz/IPtProxy.git -run '^TestDPCall'
if [ "${1:-}" = "test" ]; then exit 0; fi
mobile_version=v0.0.0-20260508232728-bebd421c7fa8
go install golang.org/x/mobile/cmd/gomobile@"$mobile_version"
go install golang.org/x/mobile/cmd/gobind@"$mobile_version"

notices() {
  local output="$1"; shift
  mkdir -p "$(dirname "$output")"
  cp "$project/carrier/UPSTREAM-LICENSE" "$output"
  go list -deps -f '{{if .Module}}{{.Module.Dir}}{{end}}' "$@" | sort -u | while IFS= read -r module; do
    [ -n "$module" ] || continue
    for notice in "$module"/LICENSE "$module"/LICENSE.txt "$module"/LICENSE.md "$module"/COPYING "$module"/LICENSE-3RD-PARTY.txt; do
      if [ -f "$notice" ]; then
        printf '\n\n%s\n' "$(basename "$module")" >> "$output"
        cat "$notice" >> "$output"
      fi
    done
  done
  cat "$(go env GOROOT)/LICENSE" >> "$output"
}
notices "$project/app/src/standard/assets/carrier-licenses.txt" ./dpcallmobile
"$(go env GOPATH)/bin/gomobile" bind -trimpath -ldflags="-s -w -checklinkname=0" -target=android/arm,android/arm64 -androidapi 26 -javapkg org.dpcall -o "$project/carrier/mobile.aar" ./dpcallmobile
cp go.mod standard.mod
cp go.sum standard.sum
cp tor.mod go.mod
cp tor.sum go.sum
mobile_version=v0.0.0-20260908204917-8b95e45f8d3e
go install golang.org/x/mobile/cmd/gomobile@"$mobile_version"
go install golang.org/x/mobile/cmd/gobind@"$mobile_version"
notices "$project/app/src/tor/assets/carrier-licenses.txt" ./dpcallmobile github.com/tladesignz/IPtProxy.git
"$(go env GOPATH)/bin/gomobile" bind -trimpath -ldflags="-s -w -checklinkname=0" -target=android/arm,android/arm64 -androidapi 26 -javapkg org.dpcall -o "$project/carrier/mobile-tor.aar" ./dpcallmobile github.com/tladesignz/IPtProxy.git


# Corresponding source, including the selected third-party modules and local
# replacements, is distributed beside the APKs (Tor bridges include GPL components).
go mod vendor
bundle="$project/.build/native-sources"
rm -rf "$bundle"
mkdir -p "$bundle/carrier-upstream" "$bundle/iptproxy-source"
git ls-files -z | tar --null -T - -cf - | tar -xf - -C "$bundle/carrier-upstream"
cp -a dpcallmobile vendor "$bundle/carrier-upstream/"
cp standard.mod standard.sum "$bundle/carrier-upstream/"
cp go.mod go.sum "$bundle/carrier-upstream/"
cp -a "$ipt/IPtProxy.go" "$ipt/dnstt" "$bundle/iptproxy-source/"
find "$bundle/iptproxy-source" -name .git -type f -delete
cp "$project/carrier/UPSTREAM.md" "$bundle/BUILD.md"
cp "$project/carrier/build.sh" "$bundle/build-reference.sh"
tar -czf "$project/carrier/native-sources.tar.gz" -C "$bundle" .
