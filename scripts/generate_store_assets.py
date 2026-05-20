#!/usr/bin/env python3
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT_DIR = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT_DIR / "store_assets"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/STHeiti Light.ttc",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/Library/Fonts/Arial Unicode.ttf",
    ]
    for path in candidates:
        if Path(path).exists():
            try:
                return ImageFont.truetype(path, size=size, index=1 if bold else 0)
            except OSError:
                continue
    return ImageFont.load_default()


def draw_rounded_card(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], fill: str) -> None:
    draw.rounded_rectangle(box, radius=18, fill=fill, outline="#dce2ea", width=2)


def draw_chart(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int]) -> None:
    x1, y1, x2, y2 = box
    for i in range(5):
        y = y1 + int((y2 - y1) * (i + 1) / 6)
        draw.line((x1, y, x2, y), fill="#e7ecf2", width=1)
    points = []
    width = x2 - x1
    height = y2 - y1
    for i in range(36):
        x = x1 + int(width * i / 35)
        wave = math.sin(i / 3.2) * 0.18 + math.cos(i / 6.0) * 0.12
        trend = i / 35 * 0.42
        y = y2 - int(height * (0.22 + trend + wave))
        points.append((x, y))
    draw.line(points, fill="#177e63", width=5, joint="curve")
    for x, y in points[::7]:
        draw.ellipse((x - 5, y - 5, x + 5, y + 5), fill="#177e63")
    draw.line((x1, y2, x2, y2), fill="#cbd5e1", width=2)
    draw.line((x1, y1, x1, y2), fill="#cbd5e1", width=2)


def draw_icon(draw: ImageDraw.ImageDraw, size: int) -> None:
    draw.rounded_rectangle((24, 24, size - 24, size - 24), radius=96, fill="#113b4a")
    draw.rounded_rectangle((54, 58, size - 54, size - 58), radius=62, fill="#ffffff")
    center_x = size // 2
    draw.line((center_x, 134, center_x, 366), fill="#113b4a", width=18)
    draw.arc((120, 80, size - 120, 300), 198, 342, fill="#d94f45", width=16)
    draw.arc((86, 48, size - 86, 340), 205, 335, fill="#177e63", width=14)
    points = [(134, 330), (210, 282), (286, 304), (378, 210)]
    draw.line(points, fill="#d94f45", width=20, joint="curve")
    for x, y in points:
        draw.ellipse((x - 14, y - 14, x + 14, y + 14), fill="#d94f45")
    draw.ellipse((center_x - 24, 120, center_x + 24, 168), fill="#f6c85f", outline="#113b4a", width=8)


def create_feature_graphic() -> Path:
    image = Image.new("RGB", (1024, 500), "#f5f7fa")
    draw = ImageDraw.Draw(image)
    title_font = font(56, bold=True)
    subtitle_font = font(28)
    label_font = font(22, bold=True)
    body_font = font(20)

    draw.rectangle((0, 0, 1024, 500), fill="#f5f7fa")
    draw.rectangle((0, 0, 1024, 10), fill="#d94f45")
    draw.rectangle((0, 10, 1024, 18), fill="#177e63")

    draw.text((56, 56), "Quant 交易台", fill="#113b4a", font=title_font)
    draw.text((58, 128), "股票研究、复盘、选股与历史模拟工具", fill="#405167", font=subtitle_font)
    draw.text((58, 172), "不接入交易通道，不提供买卖指令", fill="#7b4b2a", font=body_font)

    draw_rounded_card(draw, (54, 224, 480, 436), "#ffffff")
    draw.text((84, 252), "研究看板", fill="#113b4a", font=label_font)
    draw_chart(draw, (88, 294, 446, 398))

    draw_rounded_card(draw, (520, 76, 940, 436), "#ffffff")
    bullets = [
        ("多源行情样本", "#177e63"),
        ("自选池与复盘记录", "#113b4a"),
        ("历史 K 线模拟回测", "#d94f45"),
        ("账号权益与合规入口", "#7b4b2a"),
    ]
    y = 122
    for text, color in bullets:
        draw.ellipse((558, y + 5, 578, y + 25), fill=color)
        draw.text((596, y), text, fill="#17202a", font=label_font)
        y += 70

    draw.text((596, 392), "研究参考，不构成投资建议", fill="#637083", font=body_font)
    output = OUTPUT_DIR / "feature_graphic_1024x500.png"
    image.save(output)
    return output


def create_icon_preview() -> Path:
    image = Image.new("RGB", (512, 512), "#f5f7fa")
    draw = ImageDraw.Draw(image)
    draw_icon(draw, 512)
    output = OUTPUT_DIR / "icon_preview_512x512.png"
    image.save(output)
    return output


def create_promo_card() -> Path:
    image = Image.new("RGB", (1200, 630), "#f5f7fa")
    draw = ImageDraw.Draw(image)
    title_font = font(64, bold=True)
    subtitle_font = font(30)
    label_font = font(26, bold=True)
    body_font = font(22)

    draw.rectangle((0, 0, 1200, 630), fill="#f5f7fa")
    draw.rectangle((0, 0, 1200, 14), fill="#d94f45")
    draw.rectangle((0, 14, 1200, 26), fill="#177e63")

    draw.rounded_rectangle((72, 82, 372, 382), radius=58, fill="#ffffff", outline="#dce2ea", width=2)
    icon_image = Image.new("RGB", (512, 512), "#f5f7fa")
    draw_icon(ImageDraw.Draw(icon_image), 512)
    icon = icon_image.resize((240, 240))
    image.paste(icon, (102, 112))
    draw.text((96, 420), "合规研究工具", fill="#637083", font=body_font)

    draw.text((440, 98), "Quant 交易台", fill="#113b4a", font=title_font)
    draw.text((444, 184), "把选股、复盘、社区笔记和历史模拟放在一条研究链路里", fill="#405167", font=subtitle_font)

    tags = [("选股", "#177e63"), ("复盘", "#d94f45"), ("量化", "#113b4a"), ("账号权益", "#7b4b2a")]
    x = 444
    for tag, color in tags:
        width = 92 + len(tag) * 10
        draw.rounded_rectangle((x, 278, x + width, 332), radius=20, fill=color)
        draw.text((x + 26, 288), tag, fill="#ffffff", font=label_font)
        x += width + 18

    draw_chart(draw, (452, 394, 1080, 536))
    draw.text((444, 562), "仅作研究参考，不构成投资建议或交易指令", fill="#637083", font=body_font)

    output = OUTPUT_DIR / "promo_card_1200x630.png"
    image.save(output)
    return output


def write_manifest(paths: list[Path]) -> Path:
    manifest = OUTPUT_DIR / "ASSET_MANIFEST.md"
    screenshots = sorted((OUTPUT_DIR / "screenshots").glob("*.png"))
    lines = [
        "# QuantTradingApp Store Assets",
        "",
        "Generated by `scripts/generate_store_assets.py`.",
        "",
        "## Files",
        "",
    ]
    for path in paths:
        lines.append(f"- `{path.relative_to(ROOT_DIR)}`")
    if screenshots:
        lines.extend(["", "## Captured Screenshots", ""])
        for path in screenshots:
            lines.append(f"- `{path.relative_to(ROOT_DIR)}`")
    lines.extend(
        [
            "",
            "## Notes",
            "",
            "- `feature_graphic_1024x500.png` is sized for Android store feature-graphic style placement.",
            "- `icon_preview_512x512.png` is a store-facing preview asset, not a replacement for Android adaptive icon resources.",
            "- Real app screenshots should be captured with `scripts/capture_store_screenshots.sh` from the release candidate build.",
        ]
    )
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    paths = [create_feature_graphic(), create_icon_preview(), create_promo_card()]
    manifest = write_manifest(paths)
    print("Generated store assets:")
    for path in paths:
        print(path.relative_to(ROOT_DIR))
    print(manifest.relative_to(ROOT_DIR))


if __name__ == "__main__":
    main()
