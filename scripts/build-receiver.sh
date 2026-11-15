#!/usr/bin/env bash
# PC 수신 프로그램(Windows exe)을 빌드해 release/ 에 놓고, 자동 업데이트용 release/receiver.json 을 쓴다.
# 새 버전을 내려면 receiver/go/update.go 의 version 을 올리고 실행한 뒤 커밋·푸시한다.
set -euo pipefail
cd "$(dirname "$0")/../receiver/go"

go vet ./...
go test ./...
GOOS=windows GOARCH=amd64 go vet ./...
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w -H windowsgui" -o ../../release/ControlPadReceiver.exe .

version=$(sed -n 's/^const version = \([0-9]*\).*/\1/p' update.go)
sha=$(sha256sum ../../release/ControlPadReceiver.exe | cut -d' ' -f1)
printf '{\n  "version": %s,\n  "sha256": "%s"\n}\n' "$version" "$sha" > ../../release/receiver.json
echo "수신 프로그램 버전 $version"
