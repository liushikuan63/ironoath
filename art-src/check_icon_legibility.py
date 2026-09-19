#!/usr/bin/env python3
"""26px 可辨识卡口：把每张道具图缩到背包实际显示的 26×26，两两算距离，撞脸的直接判红。

职责：背包格子里图标只有 26px（`BagPanelView` 的 `applyAnyIconSprite(..., 26, 26)`），
这是玩家真正看到的尺寸。512px 母版上"一个铜箍一卷羊皮"的区别，缩到 26px 可能完全不存在 ——
所以判据必须在**显示尺寸**上做，而不是在母版上做。

用法：
  python art-src/check_icon_legibility.py [--dir <items 目录>] [--px 26] [--min 12]

阈值口径：距离 = 两张 26px 图逐像素的 |ΔR|+|ΔG|+|ΔB| 之和的平均（0..765 量纲）。
12 ≈ 一格灰阶差，是"扫一眼能分辨是不是同一张图"的经验下界；改阈值要连同下面的实测一起改。

退出码（按"量具崩≠红"的约定）：
  0 = 全部通过；1 = 有撞脸对（逐条打出来）；2 = 前置不满足（目录/依赖缺失，直接打修复命令）。
"""
import argparse
import os
import sys

# Windows 控制台默认按 GBK 编码，而本脚本的输出里有中文与 `↔`。
# 这不是洁癖：全绿时那句"最近跨族对 … ↔ …"会直接 UnicodeEncodeError 崩掉，
# 把"通过"报成"量具坏了"（2026-09-19 在 buildings 族上第一次跑到这条）。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

try:
    from PIL import Image
except ImportError:  # 前置不满足 ≠ 判据失败
    print("[legibility][FAIL-前置] 缺 Pillow。修复：pip install pillow")
    sys.exit(2)

# 同一件东西的不同档位（派生自同一母版）：它们**应该**像，不参与撞脸判定。
VARIANT_GROUPS = [
    {"item-shard-n-v1.png", "item-shard-r-v1.png", "item-shard-sr-v1.png", "item-shard-ssr-v1.png"},
    {"item-awaken-1-v1.png", "item-awaken-2-v1.png"},
    {"item-skillbook-main-v1.png", "item-skillbook-sub-v1.png"},
    {"item-book-exp-s-v1.png", "item-book-exp-m-v1.png"},
]


def group_of(name):
    for i, members in enumerate(VARIANT_GROUPS):
        if name in members:
            return i
    return None


def thumb(path, px):
    """缩到显示尺寸；alpha 先合成到中性底 —— 背包格子背后是深色面板，透明边不参与观感。"""
    with Image.open(path) as im:
        rgba = im.convert("RGBA").resize((px, px), Image.LANCZOS)
    bg = Image.new("RGBA", rgba.size, (26, 22, 20, 255))
    bg.alpha_composite(rgba)
    return bg.convert("RGB")


def distance(a, b):
    pa, pb = list(a.getdata()), list(b.getdata())
    total = sum(abs(x[0] - y[0]) + abs(x[1] - y[1]) + abs(x[2] - y[2]) for x, y in zip(pa, pb))
    return total / len(pa)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="client/assets/resources/ui/generated/items")
    ap.add_argument("--px", type=int, default=26)
    ap.add_argument("--min", type=float, default=12.0)
    args = ap.parse_args()

    if not os.path.isdir(args.dir):
        print(f"[legibility][FAIL-前置] 目录不存在：{args.dir}"
              f"（修复：在仓库根执行，或先跑 art-src/accept_to_runtime.py 产出图）")
        return 2
    names = sorted(n for n in os.listdir(args.dir) if n.endswith(".png"))
    if len(names) < 2:
        print(f"[legibility][FAIL-前置] {args.dir} 里不足两张图，本判据会恒绿 —— 判据失效不是通过")
        return 2

    cache = {n: thumb(os.path.join(args.dir, n), args.px) for n in names}
    collisions, closest = [], None
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            if group_of(a) is not None and group_of(a) == group_of(b):
                continue  # 同族档位：像是对的
            d = distance(cache[a], cache[b])
            if closest is None or d < closest[0]:
                closest = (d, a, b)
            if d < args.min:
                collisions.append((d, a, b))

    print(f"[legibility] {len(names)} 张图 @ {args.px}px，阈值 Δ>={args.min:g}，"
          f"最近跨族对 Δ={closest[0]:.1f}（{closest[1]} ↔ {closest[2]}）")
    if collisions:
        for d, a, b in sorted(collisions):
            print(f"[legibility][FAIL] Δ={d:.1f} < {args.min:g}：{a} 与 {b} 在背包里撞脸")
        print(f"[legibility] 共 {len(collisions)} 对撞脸 —— 换母题或换色族，不要靠加文字标签绕过")
        return 1
    print("[legibility] OK：跨族图标在显示尺寸下两两可辨")
    return 0


if __name__ == "__main__":
    sys.exit(main())
