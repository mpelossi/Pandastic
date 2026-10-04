"""The app's photo gates (android/.../brain/QualityGate.java) ported to Python, run on real dataset photos through
the app's path, so the gate thresholds come from data instead of guesses.

App path: the WebView turns the photo upright and shrinks it to a 640 px JPEG (quality 0.88); QualityGate then
resizes it with Bitmap.createScaledBitmap (bilinear, no anti-aliasing) to 256 x 256 for blur/dark/bright and to
64 x 64 for the plant-colour share.

Usage: modal run modal_app.py --stage photo-stats --labels p2 --coffee-split mix   (all test-split photos)
"""
import io
from collections import defaultdict

import numpy as np
from PIL import Image, ImageOps

MIN_SHARPNESS, MIN_LUMINANCE, MAX_SATURATED, MIN_PLANT_SHARE = 40, 35, 0.6, 0.10


def app_photo(path: str) -> np.ndarray:
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    scale = min(1.0, 640 / max(im.size))
    im = im.resize((round(im.width * scale), round(im.height * scale)), Image.BILINEAR)
    buffer = io.BytesIO()
    im.save(buffer, "JPEG", quality=88)
    return np.asarray(Image.open(buffer).convert("RGB"), dtype=np.float32)


def android_resize(rgb: np.ndarray, side: int) -> np.ndarray:
    """createScaledBitmap(filter=true): bilinear sampling at pixel centres, no anti-aliasing; rounded to bytes."""
    import torch
    import torch.nn.functional as F
    t = torch.from_numpy(rgb).permute(2, 0, 1)[None]
    out = F.interpolate(t, size=(side, side), mode="bilinear", align_corners=False, antialias=False)[0]
    return out.permute(1, 2, 0).round().clamp(0, 255).numpy()


def plant_share(rgb64: np.ndarray) -> float:
    c = rgb64 / 255.0
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    mx, mn = c.max(-1), c.min(-1)
    chroma = mx - mn
    ok = (mx >= 0.12) & (chroma / np.maximum(mx, 1e-9) >= 0.18)
    safe = np.maximum(chroma, 1e-9)
    hue = np.where(mx == r, ((g - b) / safe) / 6, np.where(mx == g, (2 + (b - r) / safe) / 6, (4 + (r - g) / safe) / 6))
    hue = np.where(hue < 0, hue + 1, hue)
    return float((ok & (hue >= 0.13) & (hue <= 0.45)).mean())


def gate_numbers(rgb256: np.ndarray) -> dict:
    gray = 0.299 * rgb256[..., 0] + 0.587 * rgb256[..., 1] + 0.114 * rgb256[..., 2]
    lap = gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:] - 4 * gray[1:-1, 1:-1]
    return {"luminance": float(gray.mean()), "saturated": float((gray > 250).mean()), "sharpness": float(lap.var())}


def measure(path: str) -> dict:
    try:
        rgb = app_photo(path)
    except Exception:
        return None
    return {**gate_numbers(android_resize(rgb, 256)), "plant_share": plant_share(android_resize(rgb, 64))}


def summarise(rows: list, values: list) -> dict:
    """Percentiles per source and label group, and how many real photos each current gate would turn away."""
    groups = defaultdict(list)
    for r, v in zip(rows, values):
        if v is None:
            continue
        kind = "other" if r["label"] == "other" else r["label"].split("_")[0]
        groups[f"{r['source']}/{kind}"].append(v)
    out = {}
    for key, vs in sorted(groups.items()):
        share = np.array([v["plant_share"] for v in vs])
        sharp = np.array([v["sharpness"] for v in vs])
        lum = np.array([v["luminance"] for v in vs])
        pct = lambda a, qs: {f"p{q:g}": round(float(np.percentile(a, q)), 3) for q in qs}
        out[key] = {"n": len(vs),
                    "plant_share": pct(share, (0.5, 1, 5, 10, 50)),
                    "sharpness": pct(sharp, (0.5, 1, 5, 50)),
                    "luminance": pct(lum, (1, 50)),
                    "rejected_now": {"not_a_plant": int((share < MIN_PLANT_SHARE).sum()), "blur": int((sharp < MIN_SHARPNESS).sum()),
                                     "dark": int((lum < MIN_LUMINANCE).sum())},
                    "rejected_if_share_below": {str(t): int((share < t).sum()) for t in (0.03, 0.05, 0.08, 0.10, 0.15)},
                    "rejected_if_sharpness_below": {str(t): int((sharp < t).sum()) for t in (40, 60, 80, 100, 120)}}
    return out
