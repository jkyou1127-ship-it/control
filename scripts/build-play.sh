#!/usr/bin/env bash
# 구글 플레이 스토어에 올릴 AAB 를 만든다. 결과물은 play/ControlPad-<버전>.aab (저장소에 넣지 않음).
#
# 프로젝트 맨 위에 play-signing.properties 와 업로드 키 파일이 있어야 한다:
#   storeFile=play-upload.keystore
#   storePassword=...
#   keyAlias=upload
#   keyPassword=...
# 이 두 파일은 공개 저장소에 올리지 말고 따로 보관한다.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f play-signing.properties ]; then
    echo "play-signing.properties 가 없습니다. 이 스크립트 맨 위의 설명을 보세요." >&2
    exit 1
fi

./gradlew testPlayReleaseUnitTest bundlePlayRelease -q
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
mkdir -p play
cp app/build/outputs/bundle/playRelease/app-play-release.aab "play/ControlPad-$name.aab"
echo "play/ControlPad-$name.aab"
