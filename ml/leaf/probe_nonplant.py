"""How often does a leaf model give a CONFIDENT answer on photos that are not crop leaves at all?

Random everyday photos from picsum.photos (Unsplash images: streets, landscapes, animals, objects; fetched for
testing only, kept in data/raw/, never committed) go through the app's path: upright, 640 px JPEG, the classifier
with its temperature and thresholds (incl. per-class minimums), and optionally the app's plant-colour gate
(QualityGate.plantShare < MIN_PLANT_SHARE -> `other`). Counts answers the app would give as CONFIDENT.

Usage (from ml/): python -m leaf.probe_nonplant <model_dir> [<model_dir> ...] [--n 100]
"""
import argparse
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import onnxruntime as ort
import requests

from . import class_thresholds as ct, photo_stats as ps

RAW = ct.ROOT / "data/raw/picsum"


def fetch(i: int) -> Path:
    path = RAW / f"{i}.jpg"
    if not path.exists():
        r = requests.get(f"https://picsum.photos/id/{i}/800/600", timeout=60)
        if r.status_code != 200 or not r.content.startswith(b"\xff\xd8"):
            return None
        path.write_bytes(r.content)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("models", nargs="+", type=Path)
    parser.add_argument("--n", type=int, default=100)
    parser.add_argument("--share", type=float, default=0.10, help="the app's MIN_PLANT_SHARE")
    args = parser.parse_args()
    RAW.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(12) as pool:
        paths = [p for p in pool.map(fetch, range(args.n)) if p]
    shares = [ps.plant_share(ps.android_resize(ps.app_photo(str(p)), 64)) for p in paths]
    gated = np.array(shares) < args.share
    print(f"{len(paths)} picsum photos; plant-colour gate (< {args.share}) turns away {int(gated.sum())}")
    for model_dir in args.models:
        meta = json.loads((model_dir / "leaf_classifier.json").read_text(encoding="utf-8"))
        labels = meta["labels"]
        sess = ort.InferenceSession(str(model_dir / "leaf_classifier.onnx"))
        mean, std = np.array(meta["mean"], np.float32), np.array(meta["std"], np.float32)
        probs = []
        for p in paths:
            z = sess.run(None, {"input": ct.app_input(p, meta["input_size"], mean, std)[None]})[0][0] / meta["temperature"]
            e = np.exp(z - z.max())
            probs.append(e / e.sum())
        top, answered = ct.decide(np.array(probs), labels, meta, meta.get("per_class_min_prob") or {})
        said = [labels[t] for t, a in zip(top, answered) if a]
        print(f"  {meta['version']}: CONFIDENT on {int(answered.sum())}/{len(paths)} without the gate, "
              f"{int((answered & ~gated).sum())}/{len(paths)} with it; labels said: {sorted(set(said))}")


if __name__ == "__main__":
    main()
