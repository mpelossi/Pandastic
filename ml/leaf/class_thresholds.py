"""Choose a stricter minimum probability for one label (contracts §1 `per_class_min_prob`) on RoCoLe calib plants.

Why: calling early rust "healthy" is the most harmful mistake (the farmer does nothing), so `coffee_healthy` may
need more confidence than the global threshold. The threshold is chosen on the calib plants only and then reported
on the test plants, with the model run the way the app runs it: the UI shrinks the photo to a 640 px JPEG
(quality 0.88, frontend/src/native.ts), then the classifier resizes it to 224 px (bilinear).

The RoCoLe split is reproduced from leaf/rocole_files.csv exactly as data.build_manifest makes it (by plant, seed 13).
Photos are fetched from their public S3 URLs into data/raw/rocole/photos (git-ignored).

Usage (from ml/):
  python -m leaf.class_thresholds ../android/app/src/main/assets/models --label coffee_healthy --precision 0.95
  ... --write      # also store the threshold in that folder's leaf_classifier.json
"""
import argparse
import csv
import io
import json
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import onnxruntime as ort
import requests
from PIL import Image, ImageOps

from . import config, data

ROOT = Path(__file__).resolve().parents[2]
PHOTOS = ROOT / "data/raw/rocole/photos"


def rocole_split(coffee_split: str, seed: int = 13) -> list[dict]:
    rows = []
    with open(Path(__file__).resolve().parent / config.SOURCES["rocole"]["file_list"], newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append({"label": r["label"], "group": r["group"], "filename": r["filename"], "url": r["s3_url"]})
    data._split_by_group(rows, data.COFFEE_SPLITS[coffee_split], seed, per_label=False)
    return rows


def fetch(row) -> Path:
    path = PHOTOS / row["filename"]
    if not path.exists():
        path.write_bytes(requests.get(row["url"], timeout=120).content)
    return path


def app_input(path: Path, size: int, mean, std) -> np.ndarray:
    """The app's path: the WebView turns the photo upright (EXIF orientation; most RoCoLe photos are stored sideways),
    shrinks it to a 640 px JPEG, then the classifier resizes it directly to the model input."""
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    scale = min(1.0, 640 / max(im.size))
    im = im.resize((round(im.width * scale), round(im.height * scale)), Image.BILINEAR)
    buffer = io.BytesIO()
    im.save(buffer, "JPEG", quality=88)
    im = Image.open(buffer).convert("RGB").resize((size, size), Image.BILINEAR)
    return ((np.asarray(im, np.float32) / 255 - mean) / std).transpose(2, 0, 1)


def probabilities(model_dir: Path, meta: dict, paths: list[Path]) -> np.ndarray:
    session = ort.InferenceSession(str(model_dir / "leaf_classifier.onnx"))
    mean, std = np.array(meta["mean"], np.float32), np.array(meta["std"], np.float32)
    name, out = session.get_inputs()[0].name, []
    for path in paths:
        logits = session.run(None, {name: app_input(path, meta["input_size"], mean, std)[None]})[0][0] / meta["temperature"]
        p = np.exp(logits - logits.max())
        out.append(p / p.sum())
    return np.array(out)


def decide(probs: np.ndarray, labels, meta: dict, per_class: dict):
    """What the Resolver does: top-1 answered if not `other`, p1 >= its min prob and margin >= min_margin."""
    order = np.argsort(-probs, axis=1)
    top, p1 = order[:, 0], probs[np.arange(len(probs)), order[:, 0]]
    margin = p1 - probs[np.arange(len(probs)), order[:, 1]]
    floor = np.array([per_class.get(labels[t], meta["thresholds"]["min_prob"]) for t in top])
    answered = (np.array([labels[t] != "other" for t in top])) & (p1 >= floor) & (margin >= meta["thresholds"]["min_margin"])
    return top, answered


def report(name: str, probs, truth, labels, meta, per_class, target: str) -> dict:
    top, answered = decide(probs, labels, meta, per_class)
    t = labels.index(target)
    said = answered & (top == t)
    wrong_as_target = Counter(labels[y] for y, s in zip(truth, said) if s and y != t)
    result = {
        "n": len(truth), "coverage": round(float(answered.mean()), 4),
        "selective_accuracy": round(float((top[answered] == truth[answered]).mean()), 4) if answered.any() else None,
        f"{target}_answered": int(said.sum()),
        f"{target}_precision": round(float((truth[said] == t).mean()), 4) if said.any() else None,
        f"wrongly_called_{target}": dict(wrong_as_target),
    }
    print(f"  {name}: {json.dumps(result)}")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model_dir", type=Path)
    parser.add_argument("--label", default="coffee_healthy")
    parser.add_argument("--precision", type=float, default=0.95)
    parser.add_argument("--coffee-split", default=None, help="default: the model's own (training.coffee_split), else mix")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    meta = json.loads((args.model_dir / "leaf_classifier.json").read_text(encoding="utf-8"))
    labels = meta["labels"]
    split = args.coffee_split or meta.get("training", {}).get("coffee_split") or "mix"
    rows = [r for r in rocole_split(split) if r["label"] in labels]
    PHOTOS.mkdir(parents=True, exist_ok=True)
    sets = {name: [r for r in rows if r["split"] == name] for name in ("calib", "test")}
    print(f"{meta['version']}, RoCoLe split '{split}': calib {len(sets['calib'])} photos, test {len(sets['test'])} photos")
    with ThreadPoolExecutor(12) as pool:
        paths = {name: list(pool.map(fetch, rs)) for name, rs in sets.items()}
    probs = {name: probabilities(args.model_dir, meta, paths[name]) for name in sets}
    truth = {name: np.array([labels.index(r["label"]) for r in rs]) for name, rs in sets.items()}

    current = dict(meta.get("per_class_min_prob") or {})
    print("before:")
    before = {name: report(name, probs[name], truth[name], labels, meta, current, args.label) for name in sets}
    # The lowest threshold (most coverage) at which the calib precision of the label reaches the target.
    t = labels.index(args.label)
    chosen = None
    for floor in np.arange(meta["thresholds"]["min_prob"], 1.0, 0.01):
        trial = {**current, args.label: round(float(floor), 2)}
        top, answered = decide(probs["calib"], labels, meta, trial)
        said = answered & (top == t)
        if said.any() and (truth["calib"][said] == t).mean() >= args.precision:
            chosen = round(float(floor), 2)
            break
    if chosen is None:
        print(f"no threshold reaches precision {args.precision} for {args.label} on calib")
        return
    print(f"chosen on calib: per_class_min_prob[{args.label}] = {chosen}")
    after_map = {**current, args.label: chosen}
    print("after:")
    after = {name: report(name, probs[name], truth[name], labels, meta, after_map, args.label) for name in sets}
    if args.write:
        meta["per_class_min_prob"] = after_map
        meta.setdefault("eval", {})["per_class_min_prob_check"] = {
            "label": args.label, "target_precision": args.precision, "chosen_on": f"RoCoLe calib plants ({split})",
            "pipeline": "640 px JPEG q0.88 then 224 bilinear, as the app", "before": before["test"], "after": after["test"]}
        (args.model_dir / "leaf_classifier.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        print(f"wrote per_class_min_prob into {args.model_dir / 'leaf_classifier.json'}")


if __name__ == "__main__":
    main()
