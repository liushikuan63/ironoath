#!/usr/bin/env python3
"""A17 舞台底图的包体量测 + 平铺接缝判据：先量再画，不靠"看起来差不多"。

职责：对候选底图给出**决策要用的三个数**——
  ① 传输字节（调色板量化后的 PNG 实际大小，不是原图）；
  ② 解码内存（宽×高×4，微信侧真正吃内存的是解码后的位图，不是文件字节）；
  ③ 铺满一块舞台要**几张这样的图**（决定分包里要不要按区域拆）。
外加一条硬判据：**可平铺的地表图必须真的能平铺** —— 对边逐像素比对，
差得多的图放进引擎就是一道亮缝，而"边界无接缝"是 A17 的验收原文。

用法：
  python art-src/measure_stage_candidates.py [--dir <候选目录>] [--width 1280 --height 720]
退出码：0 = 量完且接缝通过；1 = 有候选未过接缝判据；2 = 前置不满足（缺依赖/目录）。
"""
import argparse
import os
import sys

try:
    from PIL import Image
except ImportError:
    print("[stage-measure][FAIL-前置] 缺 Pillow。修复：pip install pillow")
    sys.exit(2)

# 接缝判据：对边（左右、上下）逐像素平均绝对差，0..255 量纲。
# 24 是"肉眼在正常缩放下读不出缝"的经验上界；生成器不保证无缝，所以这条经常是红的——
# 红了不代表不能用，代表**必须先裁掉边缘不连续带或重出**，不能装作没看见。
SEAM_MAX = 24.0
# 量化色数：地表/山脊这类大面积低对比图 192 色足够，再多字节涨得快而肉眼无差
QUANTIZE_COLORS = 192


def quantized(im: Image.Image) -> Image.Image:
    rgb = im.convert("RGB").quantize(colors=QUANTIZE_COLORS, method=Image.FASTOCTREE)
    return rgb


def edge_diff(im: Image.Image, axis: str) -> float:
    """axis='h' 比左右边（水平平铺），'v' 比上下边（垂直平铺）。"""
    # 用 RGB 而不是灰度：只看亮度会漏判"亮度相同但色相跳"的缝，而那种缝在引擎里正是一条彩带
    g = im.convert("RGB")
    w, h = g.size
    if axis == 'h':
        # 取 2px 宽的平均，避免单列噪声主导读数
        a = g.crop((0, 0, 2, h))
        b = g.crop((w - 2, 0, w, h))
    else:
        a = g.crop((0, 0, w, 2))
        b = g.crop((0, h - 2, w, h))
    pa, pb = list(a.getdata()), list(b.getdata())
    total = 0
    for x, y in zip(pa, pb):
        total += abs(x[0] - y[0]) + abs(x[1] - y[1]) + abs(x[2] - y[2])
    return total / (len(pa) * 3)


def measure(path: str, stage_w: int, stage_h: int):
    with Image.open(path) as raw:
        im = raw.convert("RGB")
    native = im.size
    rows = []
    for scale in (1.0, 0.75, 0.5):
        tw, th = int(native[0] * scale), int(native[1] * scale)
        if (tw, th) != native:
            im2 = im.resize((tw, th), Image.LANCZOS)
        else:
            im2 = im
        q = quantized(im2)
        tmp = os.path.join(os.path.dirname(path) or '.', f".measure-{os.getpid()}.png")
        q.save(tmp, optimize=True)
        nbytes = os.path.getsize(tmp)
        os.remove(tmp)
        # 铺满舞台需要几张：向上取整
        cols = -(-stage_w // tw)
        rows_needed = -(-stage_h // th)
        rows.append({
            'size': f'{tw}x{th}',
            'png_bytes': nbytes,
            'decode_bytes': tw * th * 4,
            'tiles_to_cover_stage': cols * rows_needed,
            'seam_h': round(edge_diff(im2, 'h'), 1),
            'seam_v': round(edge_diff(im2, 'v'), 1),
        })
    return native, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', default='art-src/generated/drafts/raw/2026-09-19')
    ap.add_argument('--width', type=int, default=1280, help='舞台显示宽（默认取 web-mobile 常见横屏基准）')
    ap.add_argument('--height', type=int, default=720)
    ap.add_argument('--prefix', default='a17-', help='只量文件名带此前缀的候选')
    ap.add_argument('--only', default='', help='只量文件名含此子串的候选')
    args = ap.parse_args()

    if not os.path.isdir(args.dir):
        print(f"[stage-measure][FAIL-前置] 目录不存在：{args.dir}"
              "（修复：在仓库根执行，或先生成候选底图）")
        return 2
    cands = [n for n in sorted(os.listdir(args.dir))
             if n.endswith('.png') and n.startswith(args.prefix)
             and (args.only == '' or args.only in n)]
    if not cands:
        print(f"[stage-measure][FAIL-前置] {args.dir} 下没有 {args.prefix}* 候选，"
              "本判据会恒绿 —— 判据失效不是通过")
        return 2

    print(f"[stage-measure] 舞台基准 {args.width}x{args.height}，量化 {QUANTIZE_COLORS} 色，"
          f"接缝阈值 Δ<={SEAM_MAX:g}")
    seam_failures = []
    for name in cands:
        native, rows = measure(os.path.join(args.dir, name), args.width, args.height)
        print(f"\n  {name}  原图 {native[0]}x{native[1]}")
        for r in rows:
            print(f"    {r['size']:>10}  PNG {r['png_bytes'] / 1024:7.1f}KB  "
                  f"解码 {r['decode_bytes'] / 1048576:5.2f}MB  "
                  f"铺满舞台需 {r['tiles_to_cover_stage']} 张  "
                  f"接缝 左右{r['seam_h']:5.1f} 上下{r['seam_v']:5.1f}")
            if 'ground' in name and max(r['seam_h'], r['seam_v']) > SEAM_MAX:
                seam_failures.append(f"{name} @ {r['size']}：左右 Δ={r['seam_h']}、上下 Δ={r['seam_v']}"
                                     f" > {SEAM_MAX:g}")
    if seam_failures:
        print("\n[stage-measure][FAIL] 地表图自称可平铺，但对边对不上：")
        for f in seam_failures:
            print(f"  - {f}")
        print("[stage-measure] 处置：裁掉边缘不连续带后重测，或直接重出 —— 不要接进运行时等玩家看见那道缝")
        return 1
    print("\n[stage-measure] OK：地表候选通过平铺判据")
    return 0


if __name__ == '__main__':
    sys.exit(main())
