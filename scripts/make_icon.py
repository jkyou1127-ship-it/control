#!/usr/bin/env python3
"""앱 아이콘 아래쪽에 들어갈 버전명 배지를 그린다(아이콘의 나머지는 res/drawable/ic_launcher_fg.xml).

    python3 scripts/make_icon.py "PIV"

app/src/main/res/drawable-nodpi/ic_launcher_ver.png (432×432 = 108dp × 4, 투명 바탕)를 쓴다.
런처가 가운데 지름 66dp 원 밖을 잘라 낼 수 있으므로 배지는 가운데 아래(y 약 70~78dp), 폭 약 41dp 안에 둔다.
publish.sh 가 빌드 전에 부른다.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONTS = [
    "/usr/share/fonts/truetype/nanum/NanumSquareRoundB.ttf",
    "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]
SCALE = 4                     # 1dp = 4px (xxxhdpi)
SIZE = 108 * SCALE
SS = 4                        # 안티에일리어싱: 4배로 그린 뒤 줄인다
RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable-nodpi")


def font(px):
    for path in FONTS:
        if os.path.exists(path):
            return ImageFont.truetype(path, px)
    return ImageFont.load_default()


def hexrgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def dp(v):
    return int(v * SCALE * SS)


# 버전마다 다른 배지 색(앞의 색을 단색으로 쓴다). 버전명으로 하나를 고르므로 같은 버전은 늘 같은 색이다.
PALETTES = [
    ("#29B6F6", "#7E57C2"),   # 하늘 → 보라
    ("#FF7043", "#EC407A"),   # 주황 → 분홍
    ("#66BB6A", "#26A69A"),   # 초록 → 청록
    ("#FFCA28", "#FF7043"),   # 노랑 → 주황
    ("#AB47BC", "#5C6BC0"),   # 보라 → 남색
    ("#26C6DA", "#66BB6A"),   # 청록 → 초록
    ("#EF5350", "#AB47BC"),   # 빨강 → 보라
    ("#42A5F5", "#26C6DA"),   # 파랑 → 하늘
    ("#EC407A", "#FFA726"),   # 분홍 → 주황
    ("#9CCC65", "#FFEE58"),   # 연두 → 노랑
]


def palette(text):
    """버전명으로 배지 색 한 쌍을 고른다(파이썬 실행마다 바뀌는 hash() 대신 글자 코드 합을 쓴다)."""
    n = 0
    for ch in text:
        n = (n * 31 + ord(ch)) % 1_000_003
    return PALETTES[n % len(PALETTES)]


def badge(text):
    """버전명 배지: 버전마다 다른 단색 알약(그라데이션 없음), 부드러운 그림자, 굵은 글자."""
    c_left, c_right = palette(text)
    big = SIZE * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    max_w, max_h = dp(34), dp(6.5)
    size = max_h
    while size > dp(2):
        f = font(size)
        l, t, rr, b = d.textbbox((0, 0), text, font=f)
        if rr - l <= max_w and b - t <= max_h:
            break
        size -= SS
    l, t, rr, b = d.textbbox((0, 0), text, font=f)
    w, h = rr - l, b - t
    cx, cy = big // 2, dp(74)
    px, py = dp(3.6), dp(2.2)
    box = (cx - w // 2 - px, cy - h // 2 - py, cx + w // 2 + px, cy + h // 2 + py)
    radius = (box[3] - box[1]) // 2

    # 단색 배지(그라데이션 없음): 아래로 부드러운 그림자 한 겹만 깔고, 버전 색으로 채운다.
    shadow_b = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(shadow_b).rounded_rectangle((box[0], box[1] + dp(0.8), box[2], box[3] + dp(0.8)), radius, fill=(0, 0, 0, 110))
    img.alpha_composite(shadow_b.filter(ImageFilter.GaussianBlur(dp(0.9))))
    left = right = hexrgb(c_left)
    ImageDraw.Draw(img).rounded_rectangle(box, radius, fill=left)

    # 글자: 아래로 살짝 그림자를 깔고 흰 글자
    tx, ty = cx - w // 2 - l, cy - h // 2 - t
    shadow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    # 밝은 배지(노랑 · 연두 등)에는 진한 글자, 어두운 배지에는 흰 글자
    lum = sum((0.299, 0.587, 0.114)[i] * (left[i] + right[i]) / 2 for i in range(3)) / 255
    light = lum > 0.6
    ImageDraw.Draw(shadow).text((tx, ty + dp(0.5)), text, font=f, fill=(255, 255, 255, 120) if light else (20, 10, 60, 160))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(dp(0.4))))
    ImageDraw.Draw(img).text((tx, ty), text, font=f, fill=(30, 30, 36, 255) if light else (255, 255, 255, 255))
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def render(text):
    os.makedirs(RES, exist_ok=True)
    out = os.path.join(RES, "ic_launcher_ver.png")
    badge(text).save(out)
    return out


if __name__ == "__main__":
    print(render(sys.argv[1] if len(sys.argv) > 1 else "Control Pad"))
