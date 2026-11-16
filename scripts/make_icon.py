#!/usr/bin/env python3
"""앱 아이콘(바탕 + 앞면)을 그린다. 앞면 아래쪽에 버전명이 들어간다.

    python3 scripts/make_icon.py "PIV"

쓰는 파일(둘 다 432×432, 108dp × 4):
  app/src/main/res/drawable-nodpi/ic_launcher_bg.png   바탕: 남색 → 보라 대각선 그라데이션, 은은한 빛
  app/src/main/res/drawable-nodpi/ic_launcher_ver.png  앞면: 둥근 버튼 세 개(빛 · 그림자) + 버전명 알약
런처는 가운데 지름 66dp 원 밖을 잘라 낼 수 있으므로, 그림과 글자는 그 안에 둔다. publish.sh 가 빌드 전에 부른다.
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


def background():
    big = SIZE * SS
    img = Image.new("RGBA", (big, big))
    top, bottom = hexrgb("#1B2A4E"), hexrgb("#3B1D5E")
    px = img.load()
    for y in range(0, big, SS):
        for x in range(0, big, SS):
            t = (x + y) / (2 * big)
            c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)) + (255,)
            for yy in range(SS):
                for xx in range(SS):
                    px[x + xx, y + yy] = c
    # 왼쪽 위에서 번지는 은은한 빛
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((dp(-10), dp(-10), dp(70), dp(70)), fill=(120, 200, 255, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(dp(14)))
    img.alpha_composite(glow)
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def key(layer, box, color, radius):
    """둥근 버튼 하나: 아래 그림자, 본체, 위쪽 밝은 띠."""
    x0, y0, x1, y1 = box
    shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((x0, y0 + dp(1.5), x1, y1 + dp(1.5)), radius, fill=(0, 0, 0, 110))
    layer.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(dp(1.6))))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(box, radius, fill=hexrgb(color))
    # 위쪽 절반에 흰 빛을 살짝 얹어 입체감
    shine = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    ImageDraw.Draw(shine).rounded_rectangle((x0 + dp(1), y0 + dp(1), x1 - dp(1), y0 + (y1 - y0) * 0.48), radius, fill=(255, 255, 255, 60))
    layer.alpha_composite(shine)


def foreground(text):
    big = SIZE * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    r = dp(4)
    key(img, (dp(31), dp(31), dp(52), dp(47)), "#4FC3F7", r)
    key(img, (dp(56), dp(31), dp(77), dp(47)), "#FFB74D", r)
    key(img, (dp(31), dp(51), dp(77), dp(64)), "#81C784", r)

    # 버전명 알약: 반투명 흰 테두리의 어두운 알약 + 흰 글자
    d = ImageDraw.Draw(img)
    max_w, max_h = dp(36), dp(7)
    size = max_h
    while size > dp(2):
        f = font(size)
        l, t, rr, b = d.textbbox((0, 0), text, font=f)
        if rr - l <= max_w and b - t <= max_h:
            break
        size -= SS
    l, t, rr, b = d.textbbox((0, 0), text, font=f)
    w, h = rr - l, b - t
    cx, cy = big // 2, dp(73)
    px, py = dp(3.2), dp(2)
    pill = (cx - w // 2 - px, cy - h // 2 - py, cx + w // 2 + px, cy + h // 2 + py)
    d.rounded_rectangle(pill, radius=(h // 2 + py), fill=(10, 12, 28, 170), outline=(255, 255, 255, 90), width=dp(0.5))
    d.text((cx - w // 2 - l, cy - h // 2 - t), text, font=f, fill=(255, 255, 255, 255))
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def render(text):
    os.makedirs(RES, exist_ok=True)
    background().save(os.path.join(RES, "ic_launcher_bg.png"))
    out = os.path.join(RES, "ic_launcher_ver.png")
    foreground(text).save(out)
    return out


if __name__ == "__main__":
    print(render(sys.argv[1] if len(sys.argv) > 1 else "Control Pad"))
