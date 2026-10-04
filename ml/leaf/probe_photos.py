"""Probe: how does a trained leaf model do on RoCoLe (Ecuador smartphone photos of coffee leaves on the plant)?

Downloads a sample of RoCoLe photos (healthy + rust, CC BY 4.0) from Mendeley (works from a residential IP; Mendeley
blocks cloud IPs) and runs the ONNX model the way the app does: direct bilinear resize, /255, ImageNet mean/std,
calibrated softmax and the shipped thresholds. It needs no GPU and no Modal: a quick check of whether a model
recognises farmer-style photos before installing it.

Usage (from ml/): python -m leaf.probe_photos artifacts/<version> [per_class]
"""
import csv
import json
import random
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import onnxruntime as ort
import requests
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "data/raw/rocole"
BASE = "https://data.mendeley.com/public-api/datasets/c5yvn32dzg"
PHOTOS = "1ca51fea-39e6-483f-a3f4-7e54cf3b4fd8"
LABELS_CSV = ("https://data.mendeley.com/public-files/datasets/c5yvn32dzg/files/"
              "e39cecdd-8dca-445a-b734-10229427f2fa/file_downloaded")


def classes() -> dict:
    """RoCoLe file name -> classification (healthy, rust_level_1..4, red_spider_mite)."""
    path = RAW / "RoCoLE-csv.csv"
    if not path.exists():
        path.write_bytes(requests.get(LABELS_CSV, timeout=120).content)
    out = {}
    with open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            try:
                out[r["External ID"]] = json.loads(r["Label"]).get("classification")
            except (ValueError, AttributeError):
                pass
    return out


def listing() -> dict:
    # The API returns the first 1,000 of the 1,560 photos, which is plenty for a probe.
    r = requests.get(f"{BASE}/files", params={"folder_id": PHOTOS, "version": 2}, timeout=60)
    return {x["filename"]: x["content_details"]["download_url"] for x in r.json()}


def fetch(item) -> Path:
    name, url = item
    path = RAW / "photos" / name
    if not path.exists():
        path.write_bytes(requests.get(url, timeout=120).content)
    return path


def main():
    model_dir = Path(sys.argv[1])
    per_class = int(sys.argv[2]) if len(sys.argv) > 2 else 100
    (RAW / "photos").mkdir(parents=True, exist_ok=True)
    meta = json.loads((model_dir / "leaf_classifier.json").read_text(encoding="utf-8"))
    labels, temperature = meta["labels"], meta["temperature"]
    min_prob, min_margin = meta["thresholds"]["min_prob"], meta["thresholds"]["min_margin"]
    per_class_min = meta.get("per_class_min_prob") or {}
    cls, files = classes(), listing()
    groups = defaultdict(list)
    for name in files:
        c = cls.get(name) or ""
        if c == "healthy":
            groups["coffee_healthy"].append(name)
        elif c.startswith("rust"):
            groups["coffee_rust"].append(name)
    rng, sample = random.Random(0), []
    for label, names in sorted(groups.items()):
        rng.shuffle(names)
        sample += [(n, label) for n in names[:per_class]]
    with ThreadPoolExecutor(12) as pool:
        paths = list(pool.map(fetch, [(n, files[n]) for n, _ in sample]))

    session = ort.InferenceSession(str(model_dir / "leaf_classifier.onnx"))
    mean, std, size = np.array(meta["mean"], np.float32), np.array(meta["std"], np.float32), meta["input_size"]
    stats = defaultdict(Counter)
    for path, (_, truth) in zip(paths, sample):
        # Upright first, as the app (and training) do: most RoCoLe photos are stored sideways with an EXIF tag.
        im = ImageOps.exif_transpose(Image.open(path)).convert("RGB").resize((size, size), Image.BILINEAR)
        x = ((np.asarray(im, np.float32) / 255 - mean) / std).transpose(2, 0, 1)[None]
        logits = session.run(None, {session.get_inputs()[0].name: x})[0][0] / temperature
        p = np.exp(logits - logits.max())
        p /= p.sum()
        order = np.argsort(-p)
        top, p1, p2 = labels[order[0]], p[order[0]], p[order[1]]
        answered = top != "other" and p1 >= per_class_min.get(top, min_prob) and p1 - p2 >= min_margin
        s = stats[truth]
        s["n"] += 1
        s["top1_ok"] += top == truth
        s["answered"] += answered
        s["answered_ok"] += answered and top == truth
        s["top:" + top] += 1
    print(f"model {meta['version']}, thresholds min_prob {min_prob} margin {min_margin}, RoCoLe sample")
    for truth, s in stats.items():
        tops = ", ".join(f"{k[4:]} {v}" for k, v in s.most_common() if k.startswith("top:"))
        right = f"{s['answered_ok'] / s['answered']:.0%}" if s["answered"] else "-"
        print(f"{truth}: n {s['n']}, top-1 correct {s['top1_ok'] / s['n']:.0%}, answered {s['answered'] / s['n']:.0%}, "
              f"correct when answered {right}\n   top-1: {tops}")


if __name__ == "__main__":
    main()
