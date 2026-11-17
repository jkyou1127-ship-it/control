#!/usr/bin/env python3
"""레이아웃 커뮤니티 목록(community/index.json)을 만든다.

community/layouts/*.json (중계 서버가 올린 레이아웃) 을 읽어, 앱이 목록에 보여 줄 것만 모은다:
id · 이름 · 만든 사람 · 올린 때 · 버튼 수 · 파일 크기. 새것부터.
.github/workflows/community.yml 이 레이아웃이 올라올 때마다 부른다.
"""
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "community")


def build(root=ROOT):
    entries = []
    folder = os.path.join(root, "layouts")
    for name in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        if not name.endswith(".json"):
            continue
        path = os.path.join(folder, name)
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            layout = d.get("layout") or {}
            if layout.get("format") != "controlpad-layout":
                continue
            entries.append({
                "id": name[:-5],
                "name": str(d.get("name", ""))[:40] or "이름 없음",
                "author": str(d.get("author", ""))[:20] or "익명",
                "created": str(d.get("created", "")),
                "buttons": len(layout.get("buttons") or []),
                "tier": str(d.get("tier", ""))[:12],
                "bytes": os.path.getsize(path),
            })
        except (OSError, ValueError):
            continue
    entries.sort(key=lambda e: e["created"], reverse=True)
    return {"version": 1, "layouts": entries}


def main():
    out = os.path.join(ROOT, "index.json")
    data = build()
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("레이아웃 %d개" % len(data["layouts"]))


if __name__ == "__main__":
    sys.exit(main())
