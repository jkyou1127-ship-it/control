#!/usr/bin/env bash
# 새 빌드를 알파 채널에 올리고, 알파에 있던 빌드를 정식 채널로 내린다.
#   scripts/publish.sh "바뀐 점"
# 버그 수정은 기다리지 않고 알파와 정식에 같이 올린다.
#   scripts/publish.sh --fix "고친 점"
# 알파에만 올리고 정식은 건드리지 않는다(정식에는 수정만 따로 넣은 빌드를 올린 경우).
#   scripts/publish.sh --alpha-only "고친 점"
# 실행 전에 app/build.gradle.kts 의 versionCode / versionName 을 올려 둔다. 끝나면 커밋하고 푸시한다.
set -euo pipefail
cd "$(dirname "$0")/.."

fix=0
promote=1
if [ "${1:-}" = "--fix" ]; then fix=1; promote=0; shift; fi
if [ "${1:-}" = "--alpha-only" ]; then promote=0; shift; fi
notes="${1:?바뀐 점을 적어 주세요}"
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)

if [ -f release/alpha/version.json ]; then
    old=$(sed -n 's/.*"versionCode": *\([0-9]*\).*/\1/p' release/alpha/version.json)
    if [ "$code" -le "$old" ]; then
        echo "versionCode($code)가 알파에 올라가 있는 버전($old)보다 커야 합니다" >&2
        exit 1
    fi
    if [ "$promote" = 1 ]; then
        cp release/alpha/version.json release/alpha/ControlPad.apk release/   # 알파 → 정식
    fi
fi

mkdir -p release/alpha
python3 - "$code" "$name" "$notes" <<'PY'
import json, sys
json.dump({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "notes": sys.argv[3]},
          open("release/alpha/version.json", "w"), ensure_ascii=False, indent=2)
PY

python3 receiver/test_receiver.py
./gradlew testDirectReleaseUnitTest assembleDirectRelease -q
cp app/build/outputs/apk/direct/release/app-direct-release.apk release/alpha/ControlPad.apk
if [ "$fix" = 1 ]; then
    cp release/alpha/version.json release/alpha/ControlPad.apk release/   # 버그 수정은 정식에도 바로
fi
echo "알파: $name ($code) / 정식: $(sed -n 's/.*"versionName": *"\(.*\)".*/\1/p' release/version.json)"
