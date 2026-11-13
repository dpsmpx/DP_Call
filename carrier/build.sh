#!/usr/bin/env bash
set -euo pipefail
project=$(cd "$(dirname "$0")/.." && pwd)
upstream="$project/.build/carrier-upstream"
revision=a26b5555728e5b3e990b8204f7663cc742c9de3b
if [ ! -d "$upstream/.git" ]; then
  mkdir -p "$(dirname "$upstream")"
  git clone --filter=blob:none --no-checkout https://github.com/kulikov0/whitelist-bypass.git "$upstream"
fi
git -C "$upstream" checkout --detach "$revision"
[ "$(git -C "$upstream" rev-parse HEAD)" = "$revision" ]
mkdir -p "$upstream/dpcallmobile"
cp "$project"/carrier/*.go "$upstream/dpcallmobile/"
cd "$upstream"
go test -race ./dpcallmobile
if [ "${1:-}" = "test" ]; then exit 0; fi
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260508232728-bebd421c7fa8
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260508232728-bebd421c7fa8
"$(go env GOPATH)/bin/gomobile" bind -trimpath -ldflags="-s -w" -target=android/arm,android/arm64 -androidapi 26 -javapkg org.dpcall -o "$project/carrier/mobile.aar" ./dpcallmobile
