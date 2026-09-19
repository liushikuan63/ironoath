#!/usr/bin/env python3
"""从已有母版派生"同一件东西的另一个档位/另一种配色"，不重画轮廓。

职责：碎片、觉醒石这类是同一件东西的不同稀有度档，玩家靠**颜色**分层、靠轮廓认出"还是这一族"——
所以正确做法是保留母版轮廓、只换色相与饱和，而不是再生成一张新图（那会让两张长得不像一族，
玩家读成两个无关道具）。觉醒石初阶/高阶就是这条：同一枚晶体，铜座 → 金座。
反过来，名字本身就写明是不同物件的（主技能**秘卷** / 副技能**残卷**）才各自原创轮廓。

用法：python art-src/derive_rarity_variant.py <母版.png> <输出.png> <预设名 | H:Sat:Val>
色相写在 PRESETS 里：PIL 的 HSV 色相是 0..255，本作现有碎片 SR≈铜、SSR≈金，
所以 R 走苔绿、N 走冷灰，四档在一排里能按颜色排序而不用读文字。
第三种写法（`150:0.6:1.1` 这种）是给"不属于稀有度阶梯、只是要换个色族"的场合用的，
例如把三张加速令的羊皮纸按用途分色。
"""
import os
import sys

from PIL import Image, ImageEnhance

# (色相 0..255, 饱和倍率, 明度倍率)
PRESETS = {
    'r': (92, 0.55, 0.96),
    'n': (0, 0.16, 0.88),
    # 觉醒石：母版是红晶（`drafts/a10-item-variants/item-awaken-2-v0.png`），
    # 两档都从它派生 ⇒ 轮廓完全一致，只有座/晶在走稀有度色（铜=SR，金=SSR）。
    'awaken_sr': (14, 1.05, 1.15),
    'awaken_ssr': (23, 1.20, 1.40),
    # 三张加速令的羊皮纸按用途分色（母题不变：卷轴 + 火漆印）
    'scroll_train': (78, 0.85, 1.05),
    'scroll_research': (158, 0.80, 1.10),
}


def parse_tier(text: str):
    """预设名，或 `H:Sat:Val` 三元组（色相 0..255、两个倍率是浮点）。"""
    if text in PRESETS:
        return PRESETS[text]
    parts = text.split(':')
    if len(parts) == 3:
        try:
            return int(parts[0]), float(parts[1]), float(parts[2])
        except ValueError:
            pass
    return None


def derive(src: str, dst: str, tier: str) -> int:
    parsed = parse_tier(tier)
    if parsed is None:
        print(f"[derive][FAIL] 档位既不是预设名（{sorted(PRESETS)}），也不是 H:Sat:Val 三元组：{tier}")
        return 2
    hue, sat_scale, val_scale = parsed
    with Image.open(src) as im:
        rgba = im.convert("RGBA")
    alpha = rgba.split()[3]
    hsv = rgba.convert("RGB").convert("HSV")
    h, s, v = hsv.split()
    h = h.point(lambda _x: hue)
    s = s.point(lambda x: int(x * sat_scale))
    v = v.point(lambda x: min(255, int(x * val_scale)))
    out = Image.merge("HSV", (h, s, v)).convert("RGB")
    out = ImageEnhance.Contrast(out).enhance(1.06)
    out.putalpha(alpha)
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    out.save(dst, optimize=True)
    opaque = [p for p in out.getdata() if p[0] or p[1] or p[2]]
    print(f"[derive] {os.path.basename(src)} -> {os.path.basename(dst)} tier={tier} "
          f"hue={hue} sat={sat_scale} val={val_scale} 非黑像素={len(opaque)}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    sys.exit(derive(sys.argv[1], sys.argv[2], sys.argv[3]))
