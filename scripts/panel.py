#!/usr/bin/env python3
"""평가단의 정식 추가 요청(저장소 이슈)을 확인하고 반영한다. .github/workflows/panel.yml 이 부른다.

    panel.py check   요청이 유효한지 보고 GITHUB_OUTPUT 형식(ok=, reason=, summary=)으로 적는다
    panel.py apply         release/ 의 파일을 고친다
    panel.py apply alpha   실험실 기능 정식 추가의 1단계: 알파 사용자 모두에게 켠다(labs.json 의 alpha)

요청은 두 길로 온다. 중계 서버(relay/worker.js)가 평가단 코드를 확인하고 보낸 요청은
환경 변수 REQUEST_KIND / REQUEST_TARGET 으로, GitHub 이슈로 온 요청은 ISSUE_TITLE / ISSUE_AUTHOR 로 받는다.

요청은 이슈 제목으로 한다:
    [정식 요청] 빌드 1.21        지금 알파 빌드를 정식 채널에 올린다
    [정식 요청] 실험실 kps       실험실 기능을 정식 기능으로 올린다
    [숨김 요청] 실험실 kps       실험실 기능을 숨긴다(누구에게도 켜지지 않는다)
    [복귀 요청] 실험실 kps       숨긴 기능, 또는 정식이 된 기능을 실험실로 되돌린다

이슈 제목과 작성자는 누구나 정할 수 있는 값이라 환경 변수로만 받고, 정해진 모양이 아니면 버린다.
"""
import json
import os
import re
import shutil
import sys

PREFIX = "[정식 요청]"
BUILD = re.compile(r"^\[정식 요청\] 빌드 ([0-9A-Za-z][0-9A-Za-z. ]{0,19})$")
FEATURE = r"([a-z0-9][a-z0-9-]{0,39})$"
LAB = re.compile(r"^\[정식 요청\] 실험실 " + FEATURE)
HIDE = re.compile(r"^\[숨김 요청\] 실험실 " + FEATURE)
RESTORE = re.compile(r"^\[복귀 요청\] 실험실 " + FEATURE)
# 워크플로가 받는 이슈 제목의 머리말들
PREFIXES = ("[정식 요청]", "[숨김 요청]", "[복귀 요청]")


def parse_title(title):
    """('build', 버전) / ('lab' | 'hide' | 'restore', 기능 이름). 모양이 다르면 None."""
    title = title.strip()
    m = BUILD.match(title)
    if m:
        return "build", m.group(1).strip()
    for kind, pattern in (("lab", LAB), ("hide", HIDE), ("restore", RESTORE)):
        m = pattern.match(title)
        if m:
            return kind, m.group(1)
    return None


def is_member(author, owner, panel_text):
    """저장소 주인이거나 release/panel.txt 에 적힌 계정인지."""
    names = [line.strip() for line in panel_text.splitlines()]
    names = [n for n in names if n and not n.startswith("#")]
    if author.lower() == owner.lower() or "*" in names:
        return True
    return author.lower() in (n.lower() for n in names)


def short_name(name):
    """버전 이름에서 'NCP#' 같은 머리말을 뗀다. 건의에는 머리말 없이 '1.00.a' 처럼 온다."""
    short = name.split("#")[-1]
    # 중계 서버와 이슈 제목에는 영문 · 숫자 · 점 · 빈칸만 쓴다(예: 'UIUPV!' → 'UIUPV').
    return "".join(c for c in short if (c.isascii() and c.isalnum()) or c in ". ").strip()


def check_build(version, alpha, stable):
    """알파 빌드를 정식에 올려도 되는지. 안 되면 이유를, 되면 None 을 돌려준다."""
    if short_name(version) != short_name(alpha["versionName"]):
        return "요청한 빌드(%s)가 지금 알파 빌드(%s)가 아닙니다" % (version, alpha["versionName"])
    if alpha["versionCode"] <= stable["versionCode"]:
        return "빌드 %s 은(는) 이미 정식 채널에 있습니다" % version
    return None


# 요청을 받은 뒤 반영까지 기다리는 시간(초).
#   모든 요청을 바로 반영한다(기다리지 않음). 실험실 기능 정식 추가도 단계 없이 바로 정식 기능이 된다.
#   (첫 값을 늘리면 그만큼 기다렸다 반영하고, 둘째 값을 주면 알파 사용자에게 먼저 켜는 단계가 생긴다.)
WAITS = {"lab": (0, 0), "build": (0, 0), "hide": (0, 0), "restore": (0, 0)}


def plan(kind, target):
    """요청을 받았을 때 알려 줄 진행 계획."""
    return {
        "build": "빌드 %s 을(를) 바로 정식 채널에 올립니다.",
        "lab": "실험실 기능 '%s' 을(를) 바로 정식 기능으로 올립니다.",
        "hide": "실험실 기능 '%s' 을(를) 바로 숨깁니다.",
        "restore": "기능 '%s' 을(를) 바로 실험실로 되돌립니다.",
    }[kind] % target


def check_lab(feature, labs, kind="lab"):
    if feature not in labs.get("features", []):
        return "'%s' 은(는) 실험실에 없는 기능입니다" % feature
    hidden = feature in labs.get("hidden", [])
    graduated = feature in labs.get("graduated", [])
    if kind == "lab":
        if hidden:
            return "'%s' 은(는) 숨긴 기능입니다. 먼저 복귀를 건의하세요" % feature
        if graduated:
            return "'%s' 은(는) 이미 정식 기능입니다" % feature
    elif kind == "hide":
        if hidden:
            return "'%s' 은(는) 이미 숨긴 기능입니다" % feature
    elif kind == "restore":
        if not hidden and not graduated:
            return "'%s' 은(는) 이미 실험실에 있습니다" % feature
    return None


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def evaluate(title, author, owner, release="release", alpha_dir=None):
    """(종류, 대상, 안 되는 이유 또는 None). alpha_dir 를 주면 그곳의 알파 빌드를 기준으로 본다."""
    parsed = parse_title(title)
    if parsed is None:
        return None, None, ("제목은 '[정식 요청] 빌드 <버전>', '[정식 요청] 실험실 <기능>', "
                            "'[숨김 요청] 실험실 <기능>', '[복귀 요청] 실험실 <기능>' 중 하나여야 합니다")
    kind, target = parsed
    with open(os.path.join(release, "panel.txt"), encoding="utf-8") as f:
        if not is_member(author, owner, f.read()):
            return kind, target, "평가단으로 등록된 계정만 요청할 수 있습니다"
    if kind == "build":
        problem = check_build(target, load(os.path.join(alpha_dir or os.path.join(release, "alpha"), "version.json")),
                              load(os.path.join(release, "version.json")))
    else:
        problem = check_lab(target, load(os.path.join(release, "labs.json")), kind)
    return kind, target, problem


def apply(kind, target, release="release", alpha_dir=None, stage=None):
    """요청을 반영한다. stage 가 'alpha' 이면 실험실 기능 정식 추가의 1단계(알파 사용자 모두에게 켜기)만 한다."""
    if kind == "build":
        source = alpha_dir or os.path.join(release, "alpha")
        shutil.copyfile(os.path.join(source, "ControlPad.apk"), os.path.join(release, "ControlPad.apk"))
        # 평가단 OK 를 받아 올라가는 것이므로, 정식 쪽 정보에는 '평가단 OK 필요' 표시를 남기지 않는다.
        info = load(os.path.join(source, "version.json"))
        info.pop("needsPanel", None)
        with open(os.path.join(release, "version.json"), "w", encoding="utf-8") as f:
            json.dump(info, f, ensure_ascii=False, indent=2)
    else:
        path = os.path.join(release, "labs.json")
        labs = load(path)
        graduated = set(labs.get("graduated", []))
        hidden = set(labs.get("hidden", []))
        alpha = set(labs.get("alpha", []))
        if kind == "lab" and stage == "alpha":
            alpha.add(target)
        elif kind == "lab":
            graduated.add(target)
            alpha.discard(target)
        elif kind == "hide":
            hidden.add(target)
        elif kind == "restore":
            # 숨긴 기능이면 숨기기 전 상태로, 아니면 정식에서 실험실로 되돌린다.
            if target in hidden:
                hidden.discard(target)
            else:
                graduated.discard(target)
                alpha.discard(target)
        labs["graduated"] = sorted(graduated)
        labs["hidden"] = sorted(hidden)
        labs["alpha"] = sorted(alpha)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(labs, f, indent=2)
            f.write("\n")


def summary(kind, target, stage=None):
    if kind == "lab" and stage == "alpha":
        return "실험실 기능 '%s' 을(를) 알파 사용자 모두에게" % target
    return {
        "build": "빌드 %s 을(를) 정식 채널에",
        "lab": "실험실 기능 '%s' 을(를) 정식 기능으로",
        "hide": "실험실 기능 '%s' 을(를) 숨김으로",
        "restore": "기능 '%s' 을(를) 실험실로",
    }[kind] % target


def request_from_env(env):
    """(제목, 작성자, 저장소 주인). 중계 서버가 보낸 요청은 평가단 코드를 이미 확인한 것이라 주인의 요청으로 다룬다."""
    owner = env.get("REPO_OWNER", "")
    kind = env.get("REQUEST_KIND", "")
    if kind:
        head = {"build": "[정식 요청] 빌드", "lab": "[정식 요청] 실험실",
                "hide": "[숨김 요청] 실험실", "restore": "[복귀 요청] 실험실"}.get(kind, "?")
        return "%s %s" % (head, env.get("REQUEST_TARGET", "")), owner, owner
    return env.get("ISSUE_TITLE", ""), env.get("ISSUE_AUTHOR", ""), owner


def main(argv):
    # 요청을 받은 순간의 알파 빌드를 따로 떠 둔 곳(ALPHA_SNAPSHOT). 기다리는 사이에 새 알파가 올라와도
    # 요청한 그 빌드를 정식에 올린다. 없으면 지금 저장소의 알파 빌드를 본다.
    snapshot = os.environ.get("ALPHA_SNAPSHOT") or None
    kind, target, problem = evaluate(*request_from_env(os.environ), alpha_dir=snapshot)
    if argv[1:] == ["check"]:
        print("ok=%s" % ("false" if problem else "true"))
        print("reason=%s" % (problem or ""))
        print("summary=%s" % (plan(kind, target) if not problem else ""))
        first, second = WAITS.get(kind, (300, 0))
        print("wait1=%d" % first)
        print("wait2=%d" % second)
        print("staged=%s" % ("true" if second else "false"))
    elif argv[1:2] == ["apply"] and argv[2:] in ([], ["alpha"]):
        stage = argv[2] if argv[2:] else None
        if problem:
            sys.exit(problem)
        apply(kind, target, alpha_dir=snapshot, stage=stage)
        print(summary(kind, target, stage) + (" 켰습니다" if stage else " 올렸습니다"))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
