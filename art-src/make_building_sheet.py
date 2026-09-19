#!/usr/bin/env python3
"""把 15 张建筑正稿按**城景真实显示尺寸**合成一张对照图，供逐张目视。

职责：`check_icon_legibility.py` 给的是数值（两两 Δ），它说"分得开"，
但说不出"这一坨在 48px 上还读不读得出是马厩"。读不读得出只有眼睛能判 ——
本脚本就是把 15 张图放到玩家真正会看到的大小与底色上，一次看全。

为什么需要它（而不是"真跑截图"）：dev 号城里只有主城一栋，其余 14 种要在屏上出现，
得把主城升到 8 级并建满 15 类（`building.json` 的 `requireMainLevel` 最高到 8），
dev profile 里没有发资源或跳时间的口子。所以这一张是**素材级**复核，
不能读成"15 种都在场景里画对了"—— 那句话仍未验证，见素材缺口清单 §A18 落地。

尺寸口径：城格投影后的脚印宽 53~89，`paintTile` 给的图标边长 = max(26, 宽 × 0.92)，
所以最小一档是 48px。本图按 48 与 82 两档各画一遍（小档看"还认不认得出"，
大档看"细节有没有脏"），底用五区地皮里最亮的那档 (52,38,22) —— 深色素材压在深底上
才是城景里真实的对比条件。

用法：
  python art-src/make_building_sheet.py [--out client/build/a18-runtime-sheet.png]

退出码：0 出图成功；2 前置不满足（目录 / Pillow 缺失）。
"""
import argparse
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("[sheet][前置] 缺 Pillow。修复：pip install pillow")
    sys.exit(2)

SRC_DIR = "client/assets/resources/ui/generated/buildings"
LABEL_H = 16
PAD = 8
# 五区地皮里最亮的一档（crown），深色屋顶压在上面最容易糊成一团
GROUND = (52, 38, 22, 255)
SIZES = (48, 82)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="client/build/a18-runtime-sheet.png")
    args = parser.parse_args()

    if not os.path.isdir(SRC_DIR):
        print(f"[sheet][前置] 目录不存在：{SRC_DIR}（先跑 accept_to_runtime.py）")
        return 2
    names = sorted(n for n in os.listdir(SRC_DIR) if n.endswith(".png"))
    if len(names) != 15:
        print(f"[sheet][前置] {SRC_DIR} 里是 {len(names)} 张 png，应为 15 张")
        return 2

    cells = []
    for size in SIZES:
        row = []
        for name in names:
            src = Image.open(os.path.join(SRC_DIR, name)).convert("RGBA")
            # 先按 alpha 裁掉收编时方形化补出来的透明边，再铺满这一档的框
            box = src.getbbox()
            if box is not None:
                src = src.crop(box)
            tile = Image.new("RGBA", (size + PAD * 2, size + LABEL_H + PAD), GROUND)
            scaled = src.resize((size, size), Image.LANCZOS)
            tile.alpha_composite(scaled, (PAD, LABEL_H))
            draw = ImageDraw.Draw(tile)
            # 标签用文件名主干，图例就在图里，不必另配一张对照表
            draw.text((PAD, 2), name.replace("building-", "").replace("-v1.png", ""),
                      fill=(232, 210, 170, 255))
            row.append(tile)
        cells.append(row)

    # 宽度取**最宽那一行**：按第一行（48px 档）算会把 82px 档整排裁掉 —— 首跑就是这样，
    # 出图 968 宽而第二行需要 1470，右边六类直接看不见，还误以为"15 类都看过了"。
    # 每行占 `PAD + Σ宽 + 张数×PAD`（左边距 + 每格后面都跟一个间距），少算一个 PAD 就会切掉最后一格。
    needed = [sum(t.width for t in row) + PAD * (len(row) + 1) for row in cells]
    width = max(needed)
    height = sum(max(t.height for t in row) for row in cells) + PAD * (len(cells) + 1)
    sheet = Image.new("RGBA", (width, height), (18, 15, 13, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((PAD, 2), "A18 15 类建筑 @ 城景真实尺寸：上排 48px（最小那一档城格），下排 82px（最大那一档）",
              fill=(232, 210, 170, 255))
    y = LABEL_H + PAD
    for row in cells:
        x = PAD
        for tile in row:
            sheet.alpha_composite(tile, (x, y))
            x += tile.width + PAD
        y += max(t.height for t in row) + PAD

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    sheet.save(args.out)
    print(f"[sheet] 出图：{args.out}（{width}x{height}，{len(names)} 类 × {len(SIZES)} 档）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
