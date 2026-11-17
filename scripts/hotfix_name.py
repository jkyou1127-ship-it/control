#!/usr/bin/env python3
"""핫픽스 버전명을 만든다: HF년.월.일.시.분 (한국 시간, UTC+9). 예: HF2026.11.17.9.38

    python3 scripts/hotfix_name.py            # 지금 시각
핫픽스(버그 수정을 최신 · 알파에 바로 올리는 publish.sh --fix)는 이 이름을 코드명 · 빌드 번호로 쓴다.
"""
import datetime


def name(now=None):
    kst = datetime.timezone(datetime.timedelta(hours=9))
    t = (now or datetime.datetime.now(datetime.timezone.utc)).astimezone(kst)
    return "HF%d.%d.%d.%d.%02d" % (t.year, t.month, t.day, t.hour, t.minute)


def is_hotfix(version):
    return version.startswith("HF") or version.lower().startswith("hotfix")


if __name__ == "__main__":
    print(name())
