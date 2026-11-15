#!/usr/bin/env python3
"""평가단의 정식 추가 요청(저장소 이슈)을 확인하고 반영한다. .github/workflows/panel.yml 이 부른다.

    panel.py check   요청이 유효한지 보고 GITHUB_OUTPUT 형식(ok=, reason=, summary=)으로 적는다
    panel.py apply   release/ 의 파일을 고친다

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


def check_build(version, alpha, stable):
    """알파 빌드를 정식에 올려도 되는지. 안 되면 이유를, 되면 None 을 돌려준다."""
    if version != alpha["versionName"]:
        return "요청한 빌드(%s)가 지금 알파 빌드(%s)가 아닙니다" % (version, alpha["versionName"])
    if alpha["versionCode"] <= stable["versionCode"]:
        return "빌드 %s 은(는) 이미 정식 채널에 있습니다" % version
    return None


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


def evaluate(title, author, owner, release="release"):
    """(종류, 대상, 안 되는 이유 또는 None)."""
    parsed = parse_title(title)
    if parsed is None:
        return None, None, ("제목은 '[정식 요청] 빌드 <버전>', '[정식 요청] 실험실 <기능>', "
                            "'[숨김 요청] 실험실 <기능>', '[복귀 요청] 실험실 <기능>' 중 하나여야 합니다")
    kind, target = parsed
    with open(os.path.join(release, "panel.txt"), encoding="utf-8") as f:
        if not is_member(author, owner, f.read()):
            return kind, target, "평가단으로 등록된 계정만 요청할 수 있습니다"
    if kind == "build":
        problem = check_build(target, load(os.path.join(release, "alpha", "version.json")),
                              load(os.path.join(release, "version.json")))
    else:
        problem = check_lab(target, load(os.path.join(release, "labs.json")), kind)
    return kind, target, problem


def apply(kind, target, release="release"):
    if kind == "build":
        for name in ("version.json", "ControlPad.apk"):
            shutil.copyfile(os.path.join(release, "alpha", name), os.path.join(release, name))
    else:
        path = os.path.join(release, "labs.json")
        labs = load(path)
        graduated = set(labs.get("graduated", []))
        hidden = set(labs.get("hidden", []))
        if kind == "lab":
            graduated.add(target)
        elif kind == "hide":
            hidden.add(target)
        elif kind == "restore":
            # 숨긴 기능이면 숨기기 전 상태로, 아니면 정식에서 실험실로 되돌린다.
            if target in hidden:
                hidden.discard(target)
            else:
                graduated.discard(target)
        labs["graduated"] = sorted(graduated)
        labs["hidden"] = sorted(hidden)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(labs, f, indent=2)
            f.write("\n")


def summary(kind, target):
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
    kind, target, problem = evaluate(*request_from_env(os.environ))
    if argv[1:] == ["check"]:
        print("ok=%s" % ("false" if problem else "true"))
        print("reason=%s" % (problem or ""))
        print("summary=%s" % (summary(kind, target) if not problem else ""))
    elif argv[1:] == ["apply"]:
        if problem:
            sys.exit(problem)
        apply(kind, target)
        print(summary(kind, target) + " 올렸습니다")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
