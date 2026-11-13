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
# Preserve license notices in the distributed experimental APK.
licenses="$project/app/src/experimental/assets/carrier-licenses.txt"
mkdir -p "$(dirname "$licenses")"
cp "$project/carrier/UPSTREAM-LICENSE" "$licenses"
go list -deps -f '{{if .Module}}{{.Module.Dir}}{{end}}' ./dpcallmobile | sort -u | while IFS= read -r module; do
  [ -n "$module" ] || continue
  for notice in "$module"/LICENSE "$module"/LICENSE.txt "$module"/LICENSE.md "$module"/COPYING; do
    if [ -f "$notice" ]; then
      printf '\n\n%s\n' "$(basename "$module")" >> "$licenses"
      cat "$notice" >> "$licenses"
    fi
  done
 done
cat "$(go env GOROOT)/LICENSE" >> "$licenses"
"$(go env GOPATH)/bin/gomobile" bind -trimpath -ldflags="-s -w -checklinkname=0" -target=android/arm,android/arm64 -androidapi 26 -javapkg org.dpcall -o "$project/carrier/mobile.aar" ./dpcallmobile
