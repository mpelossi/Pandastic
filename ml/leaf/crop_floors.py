"""Per-crop minimum probability (contracts §1 `per_class_min_prob`), chosen on the calib split.

Why: the pipeline picks one global `min_prob` for >= 90% selective accuracy over all calib photos, which lets a
crop with noisy labels (CCMT maize) fall below 90% on its own. This gives every label of such a crop the lowest
minimum probability at which that crop's calib answers are >= `--target` right, keeps any stricter floor already
there (e.g. coffee_healthy), and reports calib and test numbers per crop before and after.

Input: `probs.json` next to the model, written by `modal run modal_app.py --stage probs --version <v>` (the exported
ONNX's probabilities on the calib and test splits, same preprocessing as the pipeline's eval).

Usage (from ml/): python -m leaf.crop_floors artifacts/<version> [--target 0.90] [--write]
"""
import argparse
import json
from pathlib import Path

import numpy as np

from . import train


def crop_floors(probs, y, labels, th, floors, target):
    """{crop: floor or None}: None = the crop already reaches `target` at its current thresholds."""
    chosen = {}
    for crop in dict.fromkeys(label.split("_")[0] for label in labels if label != "other"):
        crop_labels = [label for label in labels if label.startswith(crop + "_")]
        chosen[crop] = None
        for floor in np.arange(th["min_prob"], 0.96, 0.05):
            trial = {**floors, **{label: max(floors.get(label, 0.0), round(float(floor), 2)) for label in crop_labels}}
            m = train.by_crop(probs, y, labels, th["min_prob"], th["min_margin"], trial)[crop]
            if m["selective_accuracy"] >= target:
                chosen[crop] = None if floor <= th["min_prob"] else round(float(floor), 2)
                break
        else:
            chosen[crop] = 0.95
    return chosen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("--target", type=float, default=0.90)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    meta_path = args.model / "leaf_classifier.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    d = json.loads((args.model / "probs.json").read_text(encoding="utf-8"))
    labels, th = meta["labels"], meta["thresholds"]
    assert d["labels"] == labels
    probs, y, split = np.array(d["probs"]), np.array(d["y"]), np.array(d["split"])
    floors = dict(meta.get("per_class_min_prob") or {})
    calib, test = split == "calib", split == "test"
    chosen = crop_floors(probs[calib], y[calib], labels, th, floors, args.target)
    after = dict(floors)
    for crop, floor in chosen.items():
        if floor is not None:
            for label in labels:
                if label.startswith(crop + "_"):
                    after[label] = max(after.get(label, 0.0), floor)
    report = {}
    for name, fl in (("before", floors), ("after", after)):
        report[name] = {s: train.by_crop(probs[m], y[m], labels, th["min_prob"], th["min_margin"], fl)
                        for s, m in (("calib", calib), ("test", test))}
        overall = train.selective_metrics(probs[test], y[test], labels, th["min_prob"], th["min_margin"], fl)
        report[name]["test_overall"] = {k: round(v, 4) if isinstance(v, float) else v for k, v in overall.items()}
    print(f"{meta['version']}: per-crop floors for >= {args.target:.0%} right on calib: {chosen}")
    for name in ("before", "after"):
        print(f"  {name}: " + " | ".join(
            f"{crop} test {m['coverage_at_threshold']:.2f} answered / {m['selective_accuracy']:.3f} right"
            for crop, m in report[name]["test"].items()) + f" | overall {report[name]['test_overall']}")
    if args.write:
        meta["per_class_min_prob"] = after
        meta.setdefault("eval", {})["per_crop_min_prob_check"] = {
            "rule": f"lowest minimum probability at which the crop's calib answers are >= {args.target:.0%} right",
            "chosen_on": "calib split (all sources)", "floors": chosen,
            "test_by_crop_after": report["after"]["test"], "test_overall_after": report["after"]["test_overall"],
        }
        # The headline test numbers follow the thresholds the app will now use.
        source = np.array(d["source"])
        p, t, s = probs[test], y[test], source[test]
        m = train.selective_metrics(p, t, labels, th["min_prob"], th["min_margin"], after)
        meta["eval"].update({
            "coverage_at_threshold": round(m["coverage"], 4), "selective_accuracy": round(m["selective_accuracy"], 4),
            "other_false_accept": round(m["other_false_accept"], 4),
            "non_plant_false_accept": round(float(train.answered_mask(
                p[s == "caltech101"], labels, th["min_prob"], th["min_margin"], after)[1].mean()), 4),
            "by_crop": report["after"]["test"],
            "by_source": train.by_source(p, t, s, labels, th["min_prob"], th["min_margin"], after),
        })
        meta_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        print(f"wrote per_class_min_prob into {meta_path}")


if __name__ == "__main__":
    main()
