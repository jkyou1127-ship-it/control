#!/usr/bin/env bash
# 새 빌드를 알파 채널에 올리고, 알파에 있던 빌드를 정식 채널로 내린다.
#   scripts/publish.sh "바뀐 점"
# 버그 수정은 기다리지 않고 알파와 정식에 같이 올린다.
#   scripts/publish.sh --fix "고친 점"
# 알파에만 올리고 정식은 건드리지 않는다(정식에는 수정만 따로 넣은 빌드를 올린 경우).
#   scripts/publish.sh --alpha-only "고친 점"
# 평가단이 OK 해야 정식에 올라가는 빌드(큰 개편 등). 다음 빌드를 낼 때도 자동으로 정식에 내려가지 않는다.
#   scripts/publish.sh --needs-panel "바뀐 점"
# 실행 전에 app/build.gradle.kts 의 versionCode / versionName 을 올려 둔다. 끝나면 커밋하고 푸시한다.
set -euo pipefail
cd "$(dirname "$0")/.."

fix=0
promote=1
if [ "${1:-}" = "--fix" ]; then fix=1; promote=0; shift; fi
needs_panel=0
if [ "${1:-}" = "--alpha-only" ]; then promote=0; shift; fi
if [ "${1:-}" = "--needs-panel" ]; then promote=0; needs_panel=1; shift; fi
notes="${1:?바뀐 점을 적어 주세요}"
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)        # 코드명(버전 확인용)
build=$(sed -n 's/.*const val BUILD = "\(.*\)".*/\1/p' app/src/main/java/com/controlpad/app/Updater.kt)   # 빌드 번호(화면 표시용)

# 시험이나 빌드가 실패하면 release/ 를 시작하기 전 상태로 되돌린다(반쯤 바뀐 채로 남지 않게).
backup=$(mktemp -d)
cp -r release "$backup/"
done_ok=0
restore() { if [ "$done_ok" != 1 ]; then rm -rf release && cp -r "$backup/release" release; echo "실패해서 release/ 를 되돌렸습니다" >&2; fi; rm -rf "$backup"; }
trap restore EXIT

if [ -f release/alpha/version.json ]; then
    old=$(sed -n 's/.*"versionCode": *\([0-9]*\).*/\1/p' release/alpha/version.json)
    if [ "$code" -le "$old" ]; then
        echo "versionCode($code)가 알파에 올라가 있는 버전($old)보다 커야 합니다" >&2
        exit 1
    fi
    # 지금 알파가 평가단 OK 를 기다리는 빌드면, 다음 빌드를 낼 때도 정식으로 내리지 않는다.
    if [ "$promote" = 1 ] && grep -q '"needsPanel": true' release/alpha/version.json; then
        echo "알파 빌드가 평가단 OK 를 기다리는 중이라 정식으로 내리지 않습니다" >&2
        promote=0
    fi
    if [ "$promote" = 1 ]; then
        cp release/alpha/version.json release/alpha/ControlPad.apk release/   # 알파 → 정식
    fi
fi

mkdir -p release/alpha
python3 - "$code" "$name" "$notes" "$needs_panel" "$build" <<'PY'
import json, sys
info = {"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "build": sys.argv[5], "notes": sys.argv[3]}
if sys.argv[4] == "1":
    info["needsPanel"] = True       # 평가단 OK(빌드 정식 추가 건의)가 있어야 정식에 올라간다
json.dump(info, open("release/alpha/version.json", "w"), ensure_ascii=False, indent=2)
PY

python3 receiver/test_receiver.py
python3 scripts/test_panel.py
node --test relay/test.mjs > /dev/null
./gradlew testReleaseUnitTest assembleRelease -q
cp app/build/outputs/apk/release/app-release.apk release/alpha/ControlPad.apk
if [ "$fix" = 1 ]; then
    cp release/alpha/version.json release/alpha/ControlPad.apk release/   # 버그 수정은 정식에도 바로
fi
done_ok=1
echo "알파: $build / $name ($code) / 최신: $(sed -n 's/.*"versionName": *"\(.*\)".*/\1/p' release/version.json)"
