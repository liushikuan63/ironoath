#!/usr/bin/env python3
"""Install the redrawn inner-city base plate as a runtime asset.

Takes the normalized 4-pass erase result, quantizes it to the same 224-colour
palette budget as `city-base-v1` (package-size gate), writes
`client/assets/resources/ui/generated/city/city-base-v2.png` plus a fresh
`.png.meta` (new uuid — Cocos keys assets by uuid, reusing v1's would alias
two files), and leaves v1 untouched as the rollback point.

Usage:
  python art-src/install_city_base.py --src tmp/redraw/city-base-v2-raw.png
Exit codes: 0 ok, 1 validation failed, 2 missing dependency.
"""

import argparse
import json
import re
import sys
import uuid
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("[install][FAIL] Pillow is required")
    sys.exit(2)

DST = Path("client/assets/resources/ui/generated/city/city-base-v2.png")
V1_META = Path("client/assets/resources/ui/generated/city/city-base-v1.png.meta")
COLORS = 224


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    args = ap.parse_args()

    src = Path(args.src)
    if not src.exists():
        print(f"[install][FAIL] source missing: {src}")
        return 2
    img = Image.open(src).convert("RGB")
    if img.size != (1792, 1024):
        print(f"[install][FAIL] source is {img.size}, expected (1792, 1024)")
        return 1
    quantized = img.quantize(COLORS, method=Image.FASTOCTREE)
    DST.parent.mkdir(parents=True, exist_ok=True)
    quantized.save(DST, optimize=True)

    meta = json.loads(V1_META.read_text(encoding="utf-8"))
    new_uuid = str(uuid.uuid4())
    old_uuid = meta["uuid"]
    text = json.dumps(meta, ensure_ascii=False, indent=2)
    text = text.replace(old_uuid, new_uuid)
    text = text.replace("city-base-v1", "city-base-v2")
    meta = json.loads(text)
    # Quantized PNG keeps the same pixel geometry, but re-assert the sprite
    # frame box so a stale meta can never ship a wrong vertex box.
    frame = meta["subMetas"]["f9941"]["userData"]
    frame["width"] = 1792
    frame["height"] = 1024
    frame["rawWidth"] = 1792
    frame["rawHeight"] = 1024
    frame["vertices"]["rawPosition"] = [-896, -512, 0, 896, -512, 0, -896, 512, 0, 896, 512, 0]
    frame["vertices"]["minPos"] = [-896, -512, 0]
    frame["vertices"]["maxPos"] = [896, 512, 0]
    DST.with_suffix(".png.meta").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    size = DST.stat().st_size
    v1 = DST.with_name("city-base-v1.png").stat().st_size
    print(f"[install] wrote {DST} ({size // 1024}KB, v1 was {v1 // 1024}KB) uuid={new_uuid}")
    if not re.match(r"^[0-9a-f-]{36}$", new_uuid):
        print("[install][FAIL] generated uuid is malformed")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
