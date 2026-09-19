#!/usr/bin/env python3
"""从"常态"按钮草稿派生出其它状态草稿，写在同一目录。

职责：状态变体不靠再生成一次（两次生成的造型/纹样必然漂移，摆在一起就是两套按钮），
而是对同一张图做**确定性的亮度/饱和调整** —— alpha 原样带过去，两态切换不会抖边。

用法：python art-src/derive_button_states.py <常态草稿.png> <输出.png> [hover|disabled]
系数写死在下面的 STATES 里并打出来：改了就等于换了一套状态语言，要连带看运行期截图。
调用方（ArtCatalog.applyCommandButton）只认这里列出的状态，没图的状态不要加。
"""
import os
import sys

from PIL import Image, ImageEnhance

# hover = "这一格是当前选中项"：金属更亮、颜色更饱和，内芯跟着抬一点。
# disabled = 灰掉：去饱和 + 压暗，保持轮廓可读（它仍然是一颗按钮，只是点不动）。
STATES = {
    'hover': {'brightness': 1.30, 'color': 1.18},
    'disabled': {'brightness': 0.62, 'color': 0.25},
}


def derive(src: str, dst: str, state: str) -> int:
    factors = STATES[state]
    with Image.open(src) as im:
        rgba = im.convert("RGBA")
    # 只动 RGB，alpha 原样带过去：抠绿结果必须与常态逐像素一致，否则两态切换会抖边。
    alpha = rgba.split()[3]
    lit = ImageEnhance.Color(
        ImageEnhance.Brightness(rgba.convert("RGB")).enhance(factors['brightness'])
    ).enhance(factors['color'])
    lit.putalpha(alpha)
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    lit.save(dst, optimize=True)
    print(f"[derive] {os.path.basename(src)} -> {os.path.basename(dst)} ({state}) "
          f"brightness={factors['brightness']} color={factors['color']}")
    return 0


if __name__ == "__main__":
    state = sys.argv[3] if len(sys.argv) > 3 else 'hover'
    if len(sys.argv) not in (3, 4) or state not in STATES:
        print(__doc__)
        sys.exit(2)
    sys.exit(derive(sys.argv[1], sys.argv[2], state))
