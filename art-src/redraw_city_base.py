#!/usr/bin/env python3
"""Re-run the inner-city base-plate erase with tight masks, one region group
per call.

Why this exists: `city-base-v1` was produced by a single edit call carrying
ten *giant* rectangular masks (one covered 41%x34% of the frame).  The model
filled those with airbrushed smears and halo rings — the 贴图断裂 the player
sees on every buildable plot.  Erasing the same structures through several
calls, each with masks that hug the actual roofs/rigs, gives every fill the
model's full attention and keeps each repaired area small relative to the
real terrain around it.

Kept as ambient terrain on purpose (NOT erased): the terraced farm fields and
the fenced drill yard.  They are ground, not structures; a hillside town
always has fields, and the farm / drill-ground sprites land on them when the
player actually builds one.

The chain starts from the archived user reference and each pass feeds the
next, so the result stays a redraw (same licensing stance as v1, see
art-src/ATTRIBUTION.md) and never ships the original pixels.

Usage:
  python art-src/redraw_city_base.py            # 4 sequential passes
  python art-src/redraw_city_base.py --preview  # mask overlay only, no calls
Exit codes: 0 ok, 1 a pass failed, 2 missing dependency/credentials.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tmp"))

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("[redraw][FAIL] Pillow is required")
    sys.exit(2)

REF = "art-src/generated/concepts/a16-city-composition/city-reference-user-v0.png"
OUT_DIR = Path("tmp/redraw")

KEEP = (
    "Keep everything OUTSIDE the masked areas pixel-identical: same camera angle, same lighting "
    "direction, same colour grading, same painterly brushwork. Keep the castle keep on the hill, "
    "the ring of town walls and the gatehouse, the stone streets and stairways, the central market "
    "square with its striped tents, the ordinary townsfolk houses, the trees, the terraced fields, "
    "the distant mountains and the sky exactly as they are. No new buildings, no text, no watermark."
)
FILL = (
    "Fill each cleared area with the natural ground that continues from its immediate surroundings: "
    "dry grass with visible tufts and clumps, packed dirt, gravel, scattered stones and the footpaths "
    "that already lead into the area. The fill must carry the SAME level of fine painterly texture as "
    "the neighbouring ground — absolutely no smooth, airbrushed, blurred or flat patches, no halo or "
    "seam where the fill meets the untouched ground. "
)

# (label, x0, y0, x1, y1) normalized to the 1792x1024 reference, tight around
# the structures only.  Delineated against tmp/ref-grid.png (100px grid).
GROUPS = [
    ("west-upper", [
        ("lumber yard: log piles, stacking racks and the long open sawmill shed",
         (90, 90, 390, 205)),
        ("academy: the stone church-like hall with the tall spire and its annex",
         (385, 245, 525, 405)),
        ("quarry: the wooden crane rig, winch hut and worked rock face on the cliff",
         (55, 290, 265, 425)),
    ]),
    ("west-lower", [
        ("siege workshop: the great water wheel, its timber frame and the adjoining sheds",
         (55, 435, 335, 645)),
        ("barracks: the row of long timber stables and barracks sheds with lean-tos",
         (295, 555, 565, 770)),
        ("drill yard: the archery targets, training dummies, weapon racks and tents inside the palisade",
         (140, 670, 700, 905)),
    ]),
    ("east-upper", [
        ("farmstead: the big timber barn with the hay stacks beside the terraced fields",
         (1400, 140, 1565, 225)),
        ("embassy: the cluster of large manor houses with steep grey slate roofs",
         (1145, 295, 1405, 485)),
        ("iron mine: the timbered mine mouth and ore chute on the upper rock face",
         (1600, 340, 1755, 470)),
    ]),
    ("east-lower", [
        ("warehouse: the long stone storehouse with the big grey roof and loading bay",
         (1365, 475, 1625, 645)),
        ("hospital: the whitewashed hall with the emblem banner and its canvas awnings",
         (1175, 555, 1425, 765)),
        ("iron mine lower: the second timbered mine mouth and spoil heap on the lower rock face",
         (1600, 700, 1755, 830)),
    ]),
]


# Single-region fix-up passes.  The grouped chain leaves three kinds of
# residue the player reads as broken art: a structure the model refused to
# demolish (the academy hall), and fills that came back as a mechanical
# repeating tuft motif or a smear (the east-side plots on slopes/terraces).
# One region per call gives each fill the model's full attention; the prompts
# name the artifact to avoid instead of only naming the target.
FIX_GROUPS = [
    ("academy", (385, 245, 525, 405),
     "Demolish completely the tall stone tower with the pointed orange-brown roof, the hall "
     "building attached to it and every wall, annex and outbuilding inside the masked area, "
     "down to bare ground including their shadows. Fill with an open cobbled courtyard that "
     "continues the stone street running past it, a round stone well in the middle and two "
     "small canvas market stalls at the edge. Varied painterly texture, no repeating pattern, "
     "no smooth or airbrushed patch, no seam at the mask boundary. "),
    ("farmstead", (1400, 140, 1565, 225),
     "Replace the smeared and wedge-patterned ground inside the masked area with the dry grass "
     "hillside and ploughed field edge that continue from the left and right: varied grass "
     "clumps, scattered stones, a faint footpath, a low dry-stone field boundary. Absolutely no "
     "repeating motif, no smooth or airbrushed patch, no seam at the mask boundary. "),
    ("warehouse", (1365, 475, 1625, 645),
     "Replace the mechanical repeating shrub pattern inside the masked area with varied open "
     "ground: dry grass with irregular clumps, packed dirt, scattered rocks and a winding cart "
     "track continuing the path that enters from the left. Every clump different from its "
     "neighbours — no tiled or repeating motif, no smooth or airbrushed patch, no seam at the "
     "mask boundary. "),
    ("hospital", (1175, 555, 1425, 765),
     "Replace the mechanical repeating grass-tuft pattern inside the masked area with varied "
     "open ground: dry grass with irregular clumps, packed dirt, a few scattered rocks and the "
     "footpath that already crosses it. Every clump different from its neighbours — no tiled or "
     "repeating motif, no smooth or airbrushed patch, no seam at the mask boundary. "),
    ("mine-up", (1600, 340, 1755, 470),
     "Replace the repeating cone-shaped rock motif inside the masked area with the natural grey "
     "rock face that continues from above and below: irregular crags, scree, a few pines rooted "
     "in the crevices and a closed mine adit framed by timber. No tiled or repeating motif, no "
     "smooth or airbrushed patch, no seam at the mask boundary. "),
    ("mine-low", (1600, 700, 1755, 830),
     "Replace the repeating cone-shaped rock motif inside the masked area with the natural grey "
     "rock face and scree slope that continue from above and below, with a few pines and a "
     "winding mule track. No tiled or repeating motif, no smooth or airbrushed patch, no seam "
     "at the mask boundary. "),
]


def prompt_for(group):
    names = "; ".join(f"the {label}" for label, _ in group)
    return (
        f"Erase completely: {names}. Remove them down to the ground, including their fences, "
        f"shadows and cast shading on neighbouring ground. " + FILL + KEEP
    )


def preview():
    img = Image.open(REF).convert("RGB")
    overlay = img.copy()
    d = ImageDraw.Draw(overlay)
    for _, group in GROUPS:
        for label, (x0, y0, x1, y1) in group:
            d.rectangle([x0, y0, x1, y1], outline=(255, 60, 60), width=4)
            d.text((x0 + 6, y0 + 6), label.split(":")[0], fill=(255, 240, 150))
    overlay.save(OUT_DIR / "mask-preview.png")
    print(f"[redraw] preview -> {OUT_DIR / 'mask-preview.png'}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--fix", action="store_true", help="run the single-region fix-up passes")
    ap.add_argument("--src", default="tmp/redraw/city-base-v2-raw.png")
    ap.add_argument("--out", default="tmp/redraw/city-base-v3-raw.png")
    args = ap.parse_args()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    if args.preview:
        preview()
        return 0

    from img_edit import multipart, BASE, KEY, MODEL  # noqa: F401  (cred load side effect)
    import base64
    import io
    import json
    import urllib.error
    import urllib.request
    import uuid

    def edit(src_path, rects, prompt, out_path, work_side=1536):
        src = Image.open(src_path).convert("RGB")
        W, H = src.size
        scale = work_side / W
        work = src.resize((work_side, max(1, round(H * scale))), Image.LANCZOS)
        ww, wh = work.size
        mask = Image.new("RGBA", (ww, wh), (0, 0, 0, 255))
        d = ImageDraw.Draw(mask)
        for (x0, y0, x1, y1) in rects:
            d.rectangle([x0 / W * ww, y0 / H * wh, x1 / W * ww, y1 / H * wh], fill=(0, 0, 0, 0))
        wp, mp = OUT_DIR / "_work.png", OUT_DIR / "_mask.png"
        work.save(wp)
        mask.save(mp)
        code, data = multipart("/images/edits", {"model": MODEL, "prompt": prompt, "n": "1"}, {
            "image": ("work.png", wp.read_bytes(), "image/png"),
            "mask": ("mask.png", mp.read_bytes(), "image/png"),
        })
        print(f"[redraw] status={code} rects={len(rects)}")
        if code != 200:
            print(json.dumps(data, ensure_ascii=False)[:800] if isinstance(data, dict) else data)
            return None
        item = (data.get("data") or [{}])[0]
        if item.get("b64_json"):
            raw = base64.b64decode(item["b64_json"])
        elif item.get("url"):
            req = urllib.request.Request(item["url"], headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                              "(KHTML, like Gecko) Chrome/125.0 Safari/537.36"})
            with urllib.request.urlopen(req, timeout=180) as r:
                raw = r.read()
        else:
            print("no image in response:", json.dumps(data, ensure_ascii=False)[:400])
            return None
        Path(out_path).write_bytes(raw)
        print(f"[redraw] -> {out_path} {Image.open(io.BytesIO(raw)).size} {len(raw) // 1024}KB")
        # The relay returns whatever size the model likes (1659x948 observed).
        # Rects are authored in 1792x1024 space, so every pass output must be
        # normalized back before it becomes the next pass input — otherwise the
        # next mask drifts by the size ratio and erases the wrong ground
        # (that is exactly how the east-side fills got smeared on 2026-09-26).
        norm = Image.open(io.BytesIO(raw)).convert("RGB").resize((1792, 1024), Image.LANCZOS)
        norm.save(out_path)
        return out_path

    if args.fix:
        current = args.src
        for i, (name, box, prompt) in enumerate(FIX_GROUPS, 1):
            out = OUT_DIR / f"fix{i}-{name}.png"
            result = edit(current, [box], prompt + KEEP, out)
            if result is None:
                print(f"[redraw][FAIL] fix {i} ({name}) returned no image")
                return 1
            current = result
        Path(current).rename(args.out) if Path(current) != Path(args.out) else None
        print(f"[redraw] fix chain complete -> {args.out}")
        return 0

    current = REF
    for i, (name, group) in enumerate(GROUPS, 1):
        rects = [box for _, box in group]
        out = OUT_DIR / f"pass{i}-{name}.png"
        result = edit(current, rects, prompt_for(group), out)
        if result is None:
            print(f"[redraw][FAIL] pass {i} ({name}) returned no image")
            return 1
        current = result
    final = OUT_DIR / "city-base-v2-raw.png"
    Path(current).rename(final) if Path(current) != final else None
    print(f"[redraw] chain complete -> {final}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
