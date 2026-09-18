#!/usr/bin/env python3
"""把 art-src 的绿底草稿"收编"成客户端运行时图。

职责：process_generated.py 产出的草稿是 512px RGBA，直接进包会把首包撑爆；
本脚本做「改名 + 缩到目标边长 + 调色板量化」，产出与
client/assets/resources/ui/generated/ 下既有文件同一形态（mode P + alpha）。

命名规则（与 items/equip/heroes 三族已收编的产物逐一对齐）：
    g3-item-book_exp_s-v0.png  ->  item-book-exp-s-v1.png
即：去掉族前缀 gN-、下划线转连字符、版本号 v0 升 v1（v1 = "已收编进包"，
草稿目录里永远是 v0，两边靠这个后缀区分"待筛"与"已用"）。

用法：
    python art-src/accept_to_runtime.py \\
        --drafts art-src/generated/drafts/g4-activity-icons \\
        --out client/assets/resources/ui/generated/activities --size 128
"""
import argparse
import os
import sys

from PIL import Image


def runtime_name(draft_name: str) -> str:
    stem = draft_name[: -len(".png")]
    if stem.startswith("g") and len(stem) > 2 and stem[1].isdigit() and stem[2] == "-":
        stem = stem[3:]
    stem = stem.replace("_", "-")
    if stem.endswith("-v0"):
        stem = stem[: -len("-v0")] + "-v1"
    return stem + ".png"


def parse_size(text: str):
    """`128` = 方形，沿用草稿方形化留下的透明留白（图标族一直这么做，等比缩到 N×N 不变形）。
    `128x96` = 非方形素材（页签、面板这类）：先按 alpha bbox 裁掉方形化补出来的边，再铺满这个框。
    这时如果素材自身长宽比和目标差超过 3%，直接失败 —— 那是在把图压扁，不是收编。"""
    if "x" in text.lower():
        w, h = (int(part) for part in text.lower().split("x"))
        return (w, h)
    side = int(text)
    return (side, side)


def accept(src: str, dst: str, target) -> int:
    with Image.open(src) as im:
        rgba = im.convert("RGBA")
    if target[0] != target[1]:
        bbox = rgba.split()[3].getbbox()
        if bbox is None:
            raise SystemExit(f"[FAIL] {os.path.basename(src)} 全透明，没有可裁的内容")
        rgba = rgba.crop(bbox)
        src_ratio = rgba.width / rgba.height
        dst_ratio = target[0] / target[1]
        if abs(src_ratio - dst_ratio) / dst_ratio > 0.03:
            raise SystemExit(f"[FAIL] {os.path.basename(src)} 长宽比 {src_ratio:.3f} 与目标 "
                             f"{dst_ratio:.3f} 差超过 3% —— 换目标尺寸或换素材，不要压扁")
    # 草稿已由 process_generated.py 方形化，方形目标只做等比缩放，不再裁切。
    rgba = rgba.resize(target, Image.LANCZOS)
    # FASTOCTREE 是 PIL 里唯一能吃 alpha 的量化方法；MEDIANCUT 会先把 alpha 丢掉。
    pal = rgba.quantize(colors=255, method=Image.Quantize.FASTOCTREE)
    pal.save(dst, optimize=True)
    return os.path.getsize(dst)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--drafts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", required=True, help="方形边长（128）或 WxH（128x96，见 parse_size）")
    args = ap.parse_args()
    target = parse_size(args.size)

    if not os.path.isdir(args.drafts):
        print(f"[accept][FAIL] 草稿目录不存在：{args.drafts}", file=sys.stderr)
        return 1
    os.makedirs(args.out, exist_ok=True)

    names = sorted(n for n in os.listdir(args.drafts) if n.endswith(".png") and "-keyed" not in n)
    if not names:
        print(f"[accept][FAIL] 草稿目录里没有 png：{args.drafts}", file=sys.stderr)
        return 1

    total = 0
    for name in names:
        dst = os.path.join(args.out, runtime_name(name))
        size = accept(os.path.join(args.drafts, name), dst, target)
        total += size
        print(f"  {os.path.basename(dst):<34} {size / 1024:6.1f}KB")
    print(f"[accept] {len(names)} 张 → {args.out}，合计 {total / 1024:.1f}KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
