# 职责：把 ImageGen 产出的纯绿底草稿转成透明 PNG —— 抠绿、去绿边溢色、裁边、方形化、降采样。
# 用法：python art-src/process_generated.py <清单 json>；清单为 [{"raw":..., "out":..., "size":512}]。
# 约定：背景必须是平坦纯色（本项目用 #00FF00 系），四角像素即背景取样点。
# 输出一行/文件的核验统计（alpha 覆盖、残留绿计数），残留绿 >0.5% 时以非零码失败。
import json
import os
import sys

from PIL import Image

GREEN_HI = 110.0   # greenness = G - max(R,B) 超过该值 → 全透明（背景）
GREEN_LO = 30.0    # 低于该值 → 全不透明（实体）；两者之间线性过渡，保住抗锯齿边
TRIM_ALPHA = 16    # bbox 裁切用的最小 alpha
MARGIN = 6
RESIDUAL_LIMIT = 0.005  # 残留绿像素占比上限


def load_pairs(manifest_path: str):
    with open(manifest_path, encoding="utf-8") as f:
        return json.load(f)


def process(raw: str, out: str, size: int):
    img = Image.open(raw).convert("RGB")
    px = img.load()
    w, h = img.size
    # 背景取样：四角平均，若四角色相差过大说明背景不平坦，直接判失败
    corners = [px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]]
    spread = max(max(c) - min(c) for c in zip(*corners))
    if spread > 40:
        raise SystemExit(f"[FAIL] {os.path.basename(raw)}: 四角背景色差 {spread}，背景不平坦，勿按抠绿处理")

    rgba = Image.new("RGBA", (w, h))
    dst = rgba.load()
    opaque = 0
    residual = 0
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            greenness = g - max(r, b)
            if greenness >= GREEN_HI:
                dst[x, y] = (0, 0, 0, 0)
                continue
            alpha = 255 if greenness <= GREEN_LO else int(255 * (GREEN_HI - greenness) / (GREEN_HI - GREEN_LO))
            # 去溢色：保留像素的绿通道压到 max(r,b)+40 以内，去掉绿底反照
            g2 = min(g, max(r, b) + 40)
            if alpha > TRIM_ALPHA:
                opaque += 1
            if greenness > 60 and alpha > 200:
                residual += 1
            dst[x, y] = (r, g2, b, alpha)

    bbox = rgba.split()[3].getbbox()
    if bbox is None:
        raise SystemExit(f"[FAIL] {os.path.basename(raw)}: 全图被抠空，检查背景假设是否成立")
    x0 = max(bbox[0] - MARGIN, 0)
    y0 = max(bbox[1] - MARGIN, 0)
    x1 = min(bbox[2] + MARGIN, w)
    y1 = min(bbox[3] + MARGIN, h)
    crop = rgba.crop((x0, y0, x1, y1))
    side = max(crop.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(crop, ((side - crop.width) // 2, (side - crop.height) // 2), crop)
    final = canvas.resize((size, size), Image.LANCZOS)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    final.save(out, optimize=True)

    total = w * h
    ratio = residual / total
    flag = "OK " if ratio <= RESIDUAL_LIMIT else "WARN"
    print(f"[{flag}] {os.path.basename(out)} {size}x{size} "
          f"opaque={opaque / total:.1%} bbox={crop.size} residual_green={ratio:.3%}")
    return ratio <= RESIDUAL_LIMIT


def main():
    if len(sys.argv) != 2:
        raise SystemExit("用法: python art-src/process_generated.py <清单 json>")
    ok = True
    for item in load_pairs(sys.argv[1]):
        ok = process(item["raw"], item["out"], item["size"]) and ok
    print("ALL_OK" if ok else "SOME_WARN")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
