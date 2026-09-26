#!/usr/bin/env python3
"""Patch the repetitive-motif fills in the redrawn base plate with real terrain
copied from clean areas of the same plate.

The image-edit relay ignores the mask (proved 2026-09-26 with a magenta-fill
probe: zero magenta came back), so whole-image fix passes only drift the whole
painting — v3 gained a moat and lost its palette.  The five east-side fills
that came back as a mechanical repeating tuft/rock motif are therefore repaired
deterministically here: copy a clean patch of the same plate over the region,
tone-match it on the ring around the region, and cross-fade the edges.

Sources are hand-picked against a grid overlay and must be uniform terrain of
the same family as the target (grass for plots, terraces for the field edge,
rock face for the mine cuts).

Usage:
  python art-src/patch_city_base.py --preview   # outline targets+sources
  python art-src/patch_city_base.py --apply --out tmp/redraw/city-base-v4.png
Exit codes: 0 ok, 1 validation failed, 2 missing dependency.
"""

import argparse
import sys

try:
    import numpy as np
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    print("[patch][FAIL] numpy + Pillow are required")
    sys.exit(2)

SRC = "tmp/redraw/city-base-v2-raw.png"
FEATHER = 18

# (target x0, y0, x1, y1, source x0, y0) — source is the same size as target.
PATCHES = [
    # farmstead smear at the terrace edge: continue the terraces from the left
    (1395, 135, 1570, 230, 1180, 120),
    # warehouse plot: dry grass from the cleared west plot
    (1365, 475, 1625, 645, 300, 600),
    # hospital plot: dry grass from the cleared south-west plot
    (1175, 555, 1425, 765, 350, 650),
    # upper mine cut: rock face from the clean cliff above it
    (1600, 340, 1755, 470, 1620, 150),
    # lower mine cut: rock face from the clean cliff below it
    (1600, 700, 1755, 830, 1600, 860),
]


def ring(rgb, x0, y0, x1, y1, width=16):
    h, w = rgb.shape[:2]
    mask = np.zeros((h, w), dtype=bool)
    mask[max(0, y0):min(h, y1), max(0, x0):min(w, x1)] = True
    outer = np.zeros((h, w), dtype=bool)
    outer[max(0, y0 - width):min(h, y1 + width), max(0, x0 - width):min(w, x1 + width)] = True
    outer &= ~mask
    px = rgb[outer]
    return px.mean(axis=0), px.std(axis=0) + 1e-6


def grassness(rgb):
    """0..1 weight for "this pixel is painted grass".  This palette's grass is
    warm olive (R slightly above G, both well above B); rock, roofs, walls and
    paths are either neutral (G≈B) or dark.  Measured on the plate: grass
    0.4-0.55, rock/wall/roof/path <=0.12.  Confines the decorrelation warp so
    structures keep their exact pixels."""
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    wb = np.clip((g - b - 20) / 25.0, 0, 1)
    wr = np.clip((g - r + 50) / 30.0, 0, 1)
    return wb * wr


def warp_field(shape, seed=7):
    """Smooth pseudo-random displacement (px) as a sum of sine waves: enough
    structure to bend the repeating motif out of alignment, smooth enough that
    it never tears a contour."""
    h, w = shape
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    rng = np.random.default_rng(seed)
    dx = np.zeros((h, w))
    dy = np.zeros((h, w))
    for _ in range(4):
        lx = rng.uniform(90, 170)
        ly = rng.uniform(90, 170)
        px = rng.uniform(0, 2 * np.pi)
        py = rng.uniform(0, 2 * np.pi)
        dx += np.sin(2 * np.pi * xx / lx + px) * np.cos(2 * np.pi * yy / ly + py)
        dy += np.cos(2 * np.pi * xx / lx + py) * np.sin(2 * np.pi * yy / ly + px)
    return dx * 5.0, dy * 5.0


def sample(rgb, xs, ys):
    h, w = rgb.shape[:2]
    xs = np.clip(xs, 0, w - 1.001)
    ys = np.clip(ys, 0, h - 1.001)
    x0 = np.floor(xs).astype(np.int64)
    y0 = np.floor(ys).astype(np.int64)
    fx = (xs - x0)[..., None]
    fy = (ys - y0)[..., None]
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    return (rgb[y0, x0] * (1 - fx) * (1 - fy)
            + rgb[y0, x1] * fx * (1 - fy)
            + rgb[y1, x0] * (1 - fx) * fy
            + rgb[y1, x1] * fx * fy)


def decorrelate(rgb, amplitude_gateBlur=6):
    gate = grassness(rgb)
    gate_img = Image.fromarray((gate * 255).astype(np.uint8))
    gate = np.asarray(gate_img.filter(
        ImageFilter.GaussianBlur(amplitude_gateBlur))).astype(np.float64) / 255.0
    dx, dy = warp_field(rgb.shape[:2])
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    sx = xx + dx * gate
    sy = yy + dy * gate
    warped = sample(rgb, sx, sy)
    return warped * gate[..., None] + rgb * (1 - gate[..., None])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--warp", action="store_true",
                    help="decorrelate the repeating grass motif instead of patching")
    ap.add_argument("--src", default=SRC)
    ap.add_argument("--out", default="tmp/redraw/city-base-v4.png")
    args = ap.parse_args()

    img = Image.open(args.src).convert("RGB")
    rgb = np.asarray(img).astype(np.float64)

    if args.warp:
        out = decorrelate(rgb)
        Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(args.out)
        print(f"[patch] wrote warped {args.out}")
        return 0

    if args.preview:
        overlay = img.copy()
        d = ImageDraw.Draw(overlay)
        for (tx0, ty0, tx1, ty1, sx0, sy0) in PATCHES:
            d.rectangle([tx0, ty0, tx1, ty1], outline=(255, 60, 60), width=4)
            d.rectangle([sx0, sy0, sx0 + (tx1 - tx0), sy0 + (ty1 - ty0)],
                        outline=(60, 200, 255), width=4)
        overlay.save("tmp/redraw/patch-preview.png")
        print("[patch] preview -> tmp/redraw/patch-preview.png (red=target, cyan=source)")
        return 0

    if not args.apply:
        print("[patch] pass --preview or --apply")
        return 1

    out = rgb.copy()
    h, w = rgb.shape[:2]
    for (tx0, ty0, tx1, ty1, sx0, sy0) in PATCHES:
        th, tw = ty1 - ty0, tx1 - tx0
        if sy0 + th > h or sx0 + tw > w:
            print(f"[patch][FAIL] source for target ({tx0},{ty0}) runs off the plate")
            return 1
        patch = rgb[sy0:sy0 + th, sx0:sx0 + tw].copy()
        tgt_mean, tgt_std = ring(rgb, tx0, ty0, tx1, ty1)
        src_mean, src_std = ring(rgb, sx0, sy0, sx0 + tw, sy0 + th)
        patch = (patch - src_mean) * (tgt_std / src_std) + tgt_mean
        alpha = np.zeros((h, w), dtype=np.uint8)
        alpha[ty0:ty1, tx0:tx1] = 255
        alpha_img = Image.fromarray(alpha).filter(ImageFilter.GaussianBlur(FEATHER))
        a = (np.asarray(alpha_img).astype(np.float64) / 255.0)[..., None]
        region = out[ty0:ty1, tx0:tx1]
        out[ty0:ty1, tx0:tx1] = region * (1 - a[ty0:ty1, tx0:tx1]) + patch * a[ty0:ty1, tx0:tx1]
    Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(args.out)
    print(f"[patch] wrote {args.out} ({len(PATCHES)} patches)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
