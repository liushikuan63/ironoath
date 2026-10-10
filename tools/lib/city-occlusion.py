#!/usr/bin/env python3
"""Measure real building alpha occlusion from a Cocos screen-geometry snapshot.

Usage: python tools/lib/city-occlusion.py SNAPSHOT.json OUT.json
Exit 0: every building is at most 20% occluded; 1: invalid input or occlusion;
2: Pillow or the output environment is unavailable. No viewport clipping is used.
"""

import hashlib
import io
import json
import math
import re
import sys
from pathlib import Path

try:
    from PIL import Image, ImageChops
except ImportError:
    Image = ImageChops = None


MAX_OCCLUDED = 0.20
ALPHA_THRESHOLD = 128
MAX_RASTER_PIXELS = 16_000_000
MAX_TOTAL_RASTER_PIXELS = 64_000_000
BUILDING_SOURCE_DIR = (
    Path(__file__).resolve().parents[2]
    / "client/assets/resources/ui/generated/buildings"
).resolve()


class InputError(ValueError):
    """A missing or inconsistent observation must fail the gate."""


def _finite_number(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise InputError(f"{label}: expected a finite number")
    try:
        finite = math.isfinite(value)
    except OverflowError:
        finite = False
    if not finite:
        raise InputError(f"{label}: expected a finite number")
    return float(value)


def _positive_integer(value, label):
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise InputError(f"{label}: expected a positive integer")
    return value


def _nonempty_string(value, label):
    if not isinstance(value, str) or not value.strip():
        raise InputError(f"{label}: expected a nonempty string")
    return value


def _check_finite_tree(value, label="snapshot"):
    if isinstance(value, float) and not math.isfinite(value):
        raise InputError(f"{label}: nonfinite numeric value")
    if isinstance(value, dict):
        for key, child in value.items():
            _check_finite_tree(child, f"{label}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _check_finite_tree(child, f"{label}[{index}]")


def _corners(raw, label):
    if not isinstance(raw, list) or len(raw) != 4:
        raise InputError(f"{label}: expected four corners in TL, TR, BR, BL order")
    points = []
    for index, point in enumerate(raw):
        if not isinstance(point, dict):
            raise InputError(f"{label}[{index}]: expected an x/y object")
        points.append((
            _finite_number(point.get("x"), f"{label}[{index}].x"),
            _finite_number(point.get("y"), f"{label}[{index}].y"),
        ))
    tl, tr, br, bl = points
    edge_x = (tr[0] - tl[0], tr[1] - tl[1])
    edge_y = (bl[0] - tl[0], bl[1] - tl[1])
    lengths = (math.hypot(*edge_x), math.hypot(*edge_y))
    cross = edge_x[0] * edge_y[1] - edge_x[1] * edge_y[0]
    if min(lengths) <= 1e-6 or abs(cross) <= 1e-8 * lengths[0] * lengths[1]:
        raise InputError(f"{label}: degenerate screen quad")
    # Orthographic UI projection is affine. A perspective quad cannot be silently
    # approximated: that would measure a different rendered silhouette.
    residual = math.hypot(
        br[0] - tr[0] - bl[0] + tl[0],
        br[1] - tr[1] - bl[1] + tl[1],
    )
    if residual > max(0.001, max(lengths) * 1e-6):
        raise InputError(f"{label}: not an affine screen quad (residual={residual:.6g})")
    return points


def _alpha_mask(image, label):
    # Production PNGs use a palette and a real PNG transparency table. Converting
    # that table to RGBA preserves its alpha; inventing alpha for RGB is forbidden.
    if "A" not in image.getbands() and "transparency" not in image.info:
        raise InputError(f"{label}: PNG has no real alpha or transparency source")
    alpha = image.convert("RGBA").getchannel("A")
    mask = alpha.point([0] * ALPHA_THRESHOLD + [255] * (256 - ALPHA_THRESHOLD))
    if mask.histogram()[255] == 0:
        raise InputError(f"{label}: empty alpha >= {ALPHA_THRESHOLD} building body")
    return mask


def _project_mask(source_mask, corners):
    """Project to integer CSS pixels, keeping the complete offscreen silhouette."""
    width, height = source_mask.size
    tl, tr, _, bl = corners
    ux, uy = (tr[0] - tl[0]) / width, (tr[1] - tl[1]) / width
    vx, vy = (bl[0] - tl[0]) / height, (bl[1] - tl[1]) / height
    determinant = ux * vy - uy * vx
    if abs(determinant) <= 1e-15:
        raise InputError("screen quad has a degenerate affine transform")
    left = math.floor(min(point[0] for point in corners))
    top = math.floor(min(point[1] for point in corners))
    right = math.ceil(max(point[0] for point in corners))
    bottom = math.ceil(max(point[1] for point in corners))
    raster_width, raster_height = right - left, bottom - top
    if raster_width <= 0 or raster_height <= 0:
        raise InputError("screen quad has an empty raster extent")
    if raster_width * raster_height > MAX_RASTER_PIXELS:
        raise InputError(f"screen quad exceeds {MAX_RASTER_PIXELS} raster pixels")
    a, b, d, e = vy / determinant, -vx / determinant, -uy / determinant, ux / determinant
    # Pillow samples pixel centres with NEAREST. The same integer screen origin
    # for every tight bounding box makes intersecting crops share that lattice.
    coefficients = (
        a, b, a * (left - tl[0]) + b * (top - tl[1]),
        d, e, d * (left - tl[0]) + e * (top - tl[1]),
    )
    mask = source_mask.transform(
        (raster_width, raster_height), Image.Transform.AFFINE, coefficients,
        resample=Image.Resampling.NEAREST, fillcolor=0,
    )
    opaque_pixels = mask.histogram()[255]
    if opaque_pixels == 0:
        raise InputError("projected alpha body contains no screen pixels")
    return {"mask": mask, "bounds": (left, top, right, bottom), "opaquePixels": opaque_pixels}


def _validated_building(raw, index):
    label = f"buildings[{index}]"
    if not isinstance(raw, dict):
        raise InputError(f"{label}: expected an object")
    if "tile" not in raw or raw["tile"] is None or raw["tile"] in ("", [], {}):
        raise InputError(f"{label}.tile: missing or empty tile identity")
    for field in ("configId", "name", "frameName", "pngPath"):
        _nonempty_string(raw.get(field), f"{label}.{field}")
    if raw.get("trim") is not False:
        raise InputError(f"{label}.trim: must be exactly false")
    original = raw.get("originalSize")
    if not isinstance(original, dict):
        raise InputError(f"{label}.originalSize: expected width/height")
    width = _positive_integer(original.get("width"), f"{label}.originalSize.width")
    height = _positive_integer(original.get("height"), f"{label}.originalSize.height")
    corners = _corners(raw.get("screenCorners"), f"{label}.screenCorners")
    order = raw.get("order")
    if (not isinstance(order, list) or len(order) != 2
            or any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in order)):
        raise InputError(f"{label}.order: expected two nonnegative integer sibling indices")
    try:
        source_path = Path(raw["pngPath"]).resolve(strict=True)
    except (OSError, ValueError) as error:
        raise InputError(f"{label}.pngPath: source PNG unavailable ({error})") from error
    if (source_path.parent != BUILDING_SOURCE_DIR
            or not re.fullmatch(r"building-[a-z0-9-]+\.png", source_path.name)):
        raise InputError(f"{label}.pngPath: expected this repository's buildings/building-*.png")
    if raw["frameName"] != source_path.stem:
        raise InputError(f"{label}.frameName: does not match PNG stem {source_path.stem}")
    try:
        source_bytes = source_path.read_bytes()
        with Image.open(io.BytesIO(source_bytes)) as image:
            if image.format != "PNG":
                raise InputError(f"{label}.pngPath: source is not a PNG")
            if image.size != (width, height):
                raise InputError(
                    f"{label}.originalSize: {width}x{height} != actual PNG {image.width}x{image.height}"
                )
            source_mask = _alpha_mask(image, f"{label}.pngPath")
    except InputError:
        raise
    except (OSError, ValueError) as error:
        raise InputError(f"{label}.pngPath: cannot decode source PNG ({error})") from error
    projected = _project_mask(source_mask, corners)
    return {
        "summary": {**raw, "pngPath": str(source_path),
                    "sourcePngSha256": hashlib.sha256(source_bytes).hexdigest(),
                    "sourceAlphaPixels": source_mask.histogram()[255],
                    "rasterBounds": list(projected["bounds"])},
        "order": tuple(order), **projected,
    }


def _measure(entries):
    """Union later-painted bodies; pair contributions intentionally may overlap."""
    summaries, errors = [], []
    for target in entries:
        left, top, right, bottom = target["bounds"]
        blocked = Image.new("L", target["mask"].size, 0)
        occluders = []
        for foreground in sorted(entries, key=lambda entry: entry["order"]):
            if foreground["order"] <= target["order"]:
                continue
            fg_left, fg_top, fg_right, fg_bottom = foreground["bounds"]
            x0, y0 = max(left, fg_left), max(top, fg_top)
            x1, y1 = min(right, fg_right), min(bottom, fg_bottom)
            if x0 >= x1 or y0 >= y1:
                continue
            target_crop = (x0 - left, y0 - top, x1 - left, y1 - top)
            foreground_crop = (x0 - fg_left, y0 - fg_top, x1 - fg_left, y1 - fg_top)
            overlap = ImageChops.darker(
                target["mask"].crop(target_crop), foreground["mask"].crop(foreground_crop),
            )
            pixels = overlap.histogram()[255]
            if pixels == 0:
                continue
            previous = blocked.crop(target_crop)
            combined = ImageChops.lighter(previous, overlap)
            newly_blocked = combined.histogram()[255] - previous.histogram()[255]
            blocked.paste(combined, (target_crop[0], target_crop[1]))
            identity = foreground["summary"]
            occluders.append({
                "tile": identity["tile"], "configId": identity["configId"],
                "name": identity["name"], "frameName": identity["frameName"],
                "order": list(foreground["order"]),
                "overlapPixels": pixels, "overlapRatio": pixels / target["opaquePixels"],
                "unionAddedPixels": newly_blocked,
            })
        occluded_pixels = blocked.histogram()[255]
        ratio = occluded_pixels / target["opaquePixels"]
        passed = occluded_pixels * 5 <= target["opaquePixels"]  # Exact inclusive 20% boundary.
        summary = {
            **target["summary"], "opaquePixels": target["opaquePixels"],
            "occludedPixels": occluded_pixels, "occludedRatio": ratio,
            "occluders": occluders, "ok": passed,
        }
        summaries.append(summary)
        if not passed:
            errors.append(
                f"tile={summary['tile']} configId={summary['configId']}: "
                f"occluded {occluded_pixels}/{target['opaquePixels']} ({ratio:.6%}) > 20%"
            )
    return summaries, errors


def analyze_snapshot(snapshot):
    result = {"ok": False, "maxOccluded": MAX_OCCLUDED, "alphaThreshold": ALPHA_THRESHOLD,
              "buildings": [], "errors": []}
    if Image is None:
        result["errors"].append("Pillow is unavailable; use the existing Python environment with Pillow")
        return result, 2
    try:
        if not isinstance(snapshot, dict):
            raise InputError("snapshot: expected an object")
        _check_finite_tree(snapshot)
        observation_errors = []
        for field in ("geometryErrors", "identityErrors"):
            observations = snapshot.get(field, [])
            if not isinstance(observations, list):
                raise InputError(f"{field}: expected an array of source observation errors")
            for observation in observations:
                detail = observation if isinstance(observation, str) else json.dumps(
                    observation, ensure_ascii=False, sort_keys=True,
                )
                observation_errors.append(f"{field}: {detail}")
        result["phase"] = _nonempty_string(snapshot.get("phase"), "phase")
        canvas = snapshot.get("canvas")
        if not isinstance(canvas, dict):
            raise InputError("canvas: expected left/top/width/height")
        for field in ("left", "top", "width", "height"):
            value = _finite_number(canvas.get(field), f"canvas.{field}")
            if field in ("width", "height") and value <= 0:
                raise InputError(f"canvas.{field}: must be positive")
        result["canvas"] = canvas
        if "metadata" in snapshot:
            result["metadata"] = snapshot["metadata"]
        buildings = snapshot.get("buildings")
        if not isinstance(buildings, list) or not buildings or len(buildings) > 128:
            raise InputError("buildings: expected between 1 and 128 observed buildings")
        entries, seen_tiles, seen_orders, raster_pixels = [], set(), set(), 0
        for index, raw in enumerate(buildings):
            summary = dict(raw) if isinstance(raw, dict) else {"input": raw}
            try:
                entry = _validated_building(raw, index)
                tile_key = json.dumps(raw["tile"], sort_keys=True, ensure_ascii=False)
                if tile_key in seen_tiles:
                    raise InputError(f"buildings[{index}].tile: duplicate identity {tile_key}")
                if entry["order"] in seen_orders:
                    raise InputError(f"buildings[{index}].order: duplicate paint order {entry['order']}")
                raster_pixels += entry["mask"].width * entry["mask"].height
                if raster_pixels > MAX_TOTAL_RASTER_PIXELS:
                    raise InputError(f"buildings: exceeds {MAX_TOTAL_RASTER_PIXELS} total raster pixels")
                seen_tiles.add(tile_key)
                seen_orders.add(entry["order"])
                entries.append(entry)
                summary = entry["summary"]
            except InputError as error:
                result["errors"].append(str(error))
                summary["ok"] = False
                summary["inputError"] = str(error)
            result["buildings"].append(summary)
        if result["errors"]:
            result["errors"].extend(observation_errors)
            return result, 1
        result["buildings"], result["errors"] = _measure(entries)
        result["errors"].extend(observation_errors)
        bounds = [entry["bounds"] for entry in entries]
        result["rasterDomain"] = {
            "left": min(box[0] for box in bounds), "top": min(box[1] for box in bounds),
            "right": max(box[2] for box in bounds), "bottom": max(box[3] for box in bounds),
            "viewportClipped": False,
        }
        result["maxObservedOcclusion"] = max(row["occludedRatio"] for row in result["buildings"])
        result["ok"] = not result["errors"]
        return result, 0 if result["ok"] else 1
    except InputError as error:
        result["errors"].append(str(error))
        return result, 1


def _reject_constant(value):
    raise InputError(f"JSON contains nonfinite numeric constant {value}")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InputError(f"JSON contains duplicate key {key}")
        result[key] = value
    return result


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2:
        print("Usage: python tools/lib/city-occlusion.py SNAPSHOT.json OUT.json", file=sys.stderr)
        return 1
    try:
        with Path(args[0]).open("r", encoding="utf-8-sig") as handle:
            snapshot = json.load(handle, parse_constant=_reject_constant, object_pairs_hook=_unique_object)
        result, code = analyze_snapshot(snapshot)
    except (OSError, UnicodeError, ValueError) as error:
        result = {"ok": False, "maxOccluded": MAX_OCCLUDED, "alphaThreshold": ALPHA_THRESHOLD,
                  "buildings": [], "errors": [f"snapshot cannot be read: {error}"]}
        code = 1
    try:
        with Path(args[1]).open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(result, handle, ensure_ascii=False, allow_nan=False, indent=2)
            handle.write("\n")
    except (OSError, UnicodeError, ValueError) as error:
        print(f"city-occlusion: cannot write output JSON: {error}", file=sys.stderr)
        return 2
    print(json.dumps({
        "ok": result["ok"], "exitCode": code, "buildings": len(result["buildings"]),
        "maxObservedOcclusion": result.get("maxObservedOcclusion"), "errors": result["errors"],
    }, ensure_ascii=True, allow_nan=False))
    return code


if __name__ == "__main__":
    sys.exit(main())
