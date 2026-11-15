#!/usr/bin/env bash
# 평가단 요청을 release/ 에 반영해 커밋·푸시한다. .github/workflows/panel.yml 이 부른다.
#   panel_apply.sh          요청을 반영한다
#   panel_apply.sh alpha    실험실 기능 정식 추가의 1단계(알파 사용자 모두에게 켜기)
# 여러 요청이 겹쳐 푸시가 밀리면 최신 상태를 다시 받아 처음부터 반영한다.
# 결과 문장을 출력한다. 종료 코드: 0 반영함, 2 거절(이유를 출력), 1 계속 겹쳐서 실패
set -u
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
for attempt in 1 2 3 4 5 6; do
    git fetch -q origin "$GITHUB_REF_NAME"
    git reset -q --hard "origin/$GITHUB_REF_NAME"
    if ! result=$(python3 scripts/panel.py apply ${1:+"$1"} 2>&1); then
        echo "$result"
        exit 2
    fi
    git add release
    git commit -q -m "Panel request: $result"
    if git push -q origin "HEAD:$GITHUB_REF_NAME"; then
        echo "$result"
        exit 0
    fi
    sleep $((attempt * 3 + RANDOM % 5))
done
echo "다른 변경과 계속 겹쳐서 반영하지 못했습니다"
exit 1
