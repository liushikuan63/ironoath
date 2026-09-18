# 职责：把 ImageGen 产出的纯绿底草稿转成透明 PNG —— 抠绿、去绿边溢色、裁边、方形化、降采样。
# 用法：python art-src/process_generated.py <清单 json>；清单条目三选一：
#   单图：   {"raw":..., "out":..., "size":512}
#   母版拆格：{"raw":..., "grid":{"cols":C,"rows":R,"out_dir":...,"names":[C*R 个文件名前缀]}, "size":512}
#   直存图：  {"raw":..., "out":..., "size":W, "mode":"plain"}   # 不抠绿，仅等比压缩（场景背景用）
# 约定：抠绿条目的背景必须是平坦纯色（本项目用 #00FF00 系），四角像素即背景取样点。
# 输出一行/文件的核验统计（alpha 覆盖、残留绿计数），残留绿 >0.5% 或拆格粘连即非零码失败。
import json
import os
import sys

from PIL import Image

GREEN_HI = 110.0   # greenness = G - max(R,B) 超过该值 → 全透明（背景）
GREEN_LO = 30.0    # 低于该值 → 全不透明（实体）；两者之间线性过渡，保住抗锯齿边
TRIM_ALPHA = 16    # bbox 裁切用的最小 alpha
MARGIN = 6
RESIDUAL_LIMIT = 0.005  # 残留绿像素占比上限
TOUCH_TOLERANCE = 3     # 拆格时内容距格边界小于该值 → 判粘连


def load_pairs(manifest_path: str):
    with open(manifest_path, encoding="utf-8") as f:
        return json.load(f)


def green_samples(img: Image.Image, mode: str):
    """背景取样点：'bust' = 上边+两侧 5 点（允许半身像主体合法触底）；
    'corners' = 四角（母版拆格要求网格四周全是背景）。"""
    px = img.load()
    w, h = img.size
    if mode == "corners":
        pts = [px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]]
    else:
        pts = [px[2, 2], px[w - 3, 2], px[w // 2, 2], px[2, h // 2], px[w - 3, h // 2]]
    return pts


def key_green(img: Image.Image, sample_mode: str = "bust"):
    """RGB 绿底图 → (RGBA, 残留绿占比)。取样点必须全绿且色相一致，否则判背景假设不成立。"""
    px = img.load()
    w, h = img.size
    pts = green_samples(img, sample_mode)
    bad = [c for c in pts if c[1] - max(c[0], c[2]) <= 110]
    if bad:
        raise SystemExit(f"[FAIL] 背景取样点 {len(bad)}/{len(pts)} 不是绿底，背景假设不成立")
    spread = max(max(c) - min(c) for c in zip(*pts))
    if spread > 60:
        raise SystemExit(f"[FAIL] 绿底角色差 {spread}，背景不平坦，勿按抠绿处理")
    rgba = Image.new("RGBA", (w, h))
    dst = rgba.load()
    residual = 0
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            greenness = g - max(r, b)
            if greenness >= GREEN_HI:
                dst[x, y] = (0, 0, 0, 0)
                continue
            alpha = 255 if greenness <= GREEN_LO else int(255 * (GREEN_HI - greenness) / (GREEN_HI - GREEN_LO))
            g2 = min(g, max(r, b) + 40)  # 去溢色
            if greenness > 60 and alpha > 200:
                residual += 1
            dst[x, y] = (r, g2, b, alpha)
    return rgba, residual / (w * h)


def square_and_save(rgba: Image.Image, out: str, size: int, label: str):
    """按 alpha bbox 裁边 → 方形化 → LANCZOS 压缩 → 存盘并打统计。"""
    w, h = rgba.size
    bbox = rgba.split()[3].getbbox()
    if bbox is None:
        raise SystemExit(f"[FAIL] {label}: 全图被抠空，检查背景假设是否成立")
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
    opaque = sum(1 for a in final.split()[3].tobytes() if a > TRIM_ALPHA) / (size * size)
    print(f"[OK ] {os.path.basename(out)} {size}x{size} opaque={opaque:.1%} bbox={crop.size}")


def process_single(item):
    img = Image.open(item["raw"]).convert("RGB")
    if item.get("mode") == "plain":
        w, h = img.size
        target = item["size"]
        final = img.resize((target, round(h * target / w)), Image.LANCZOS)
        os.makedirs(os.path.dirname(item["out"]), exist_ok=True)
        final.save(item["out"], optimize=True)
        print(f"[OK ] {os.path.basename(item['out'])} plain {final.size[0]}x{final.size[1]}")
        return True
    rgba, ratio = key_green(img)
    ok = ratio <= RESIDUAL_LIMIT
    flag = "OK " if ok else "WARN"
    label = os.path.basename(item["out"])
    print(f"[{flag}] {label} residual_green={ratio:.3%}")
    square_and_save(rgba, item["out"], item["size"], label)
    return ok


def find_bands(alpha: Image.Image, axis: str, expect: int, thresh: int = 24):
    """按绿色间隔带投影切格：axis='cols' 沿 x 扫描列，'rows' 沿 y 扫描行。
    返回 expect 个 (start,end) 区间；分段数不符即失败（等分假设不可靠，投影才是真相）。"""
    w, h = alpha.size
    step_len = w if axis == "cols" else h
    other = h if axis == "cols" else w
    px = alpha.load()
    empty = []
    for i in range(step_len):
        hit = False
        for j in range(0, other, 2):
            v = px[i, j] if axis == "cols" else px[j, i]
            if v > thresh:
                hit = True
                break
        empty.append(hit)
    bands = []
    start = None
    for i, hit in enumerate(empty):
        if hit and start is None:
            start = i
        elif not hit and start is not None:
            bands.append((start, i))
            start = None
    if start is not None:
        bands.append((start, len(empty)))
    if len(bands) != expect:
        return None
    return bands


def process_grid(item):
    """母版拆格：整图抠绿 → 投影找 cols×rows 个真实间隔带（失败即报错，绝不静默等分）
    → 逐格按带裁切、方形化。"""
    img = Image.open(item["raw"]).convert("RGB")
    rgba, ratio = key_green(img, "corners")
    grid = item["grid"]
    cols, rows = grid["cols"], grid["rows"]
    names = grid["names"]
    assert len(names) == cols * rows, f"格数 {cols*rows} 与 names {len(names)} 不符"
    ok = ratio <= RESIDUAL_LIMIT
    print(f"[{'OK ' if ok else 'WARN'}] master {os.path.basename(item['raw'])} residual_green={ratio:.3%}")
    alpha = rgba.split()[3]
    col_bands = find_bands(alpha, "cols", cols)
    row_bands = find_bands(alpha, "rows", rows)
    if col_bands is None or row_bands is None:
        raise SystemExit(
            f"[FAIL] {os.path.basename(item['raw'])}: 投影分段 {col_bands and len(col_bands)}x{row_bands and len(row_bands)}"
            f" ≠ 网格 {cols}x{rows}，物体在投影方向有重叠，需重生成（加大格间距）")
    for row in range(rows):
        for col in range(cols):
            name = names[row * cols + col]
            y0, y1 = row_bands[row]
            x0, x1 = col_bands[col]
            region = rgba.crop((x0, y0, x1, y1))
            square_and_save(region, os.path.join(grid["out_dir"], name + ".png"), item["size"], name)
    return ok


def main():
    if len(sys.argv) != 2:
        raise SystemExit("用法: python art-src/process_generated.py <清单 json>")
    ok = True
    for item in load_pairs(sys.argv[1]):
        ok = (process_grid(item) if "grid" in item else process_single(item)) and ok
    print("ALL_OK" if ok else "SOME_WARN")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
