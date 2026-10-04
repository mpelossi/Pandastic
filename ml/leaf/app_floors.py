"""Minimum probabilities chosen on calib photos run through the real app (scripts/photo-eval.mjs on an emulator).

Why: the app's photo path (WebView 640 px JPEG, then Android's non-antialiased bitmap resize to 224 px) shifts some
models' probabilities away from the laptop copy of that path (class_thresholds.app_input). EfficientNet-B0 called
8 of 161 RoCoLe rust test leaves healthy in the app with floors chosen on the laptop (laptop estimate: 5).

Rules, as crop_floors.py and class_thresholds.py, with the healthy target raised to 0.99:
  crop floor: the lowest floor for all of a crop's labels at which that crop's calib answers are >= 90% right;
  healthy floor: the lowest floor (>= the crop floor) at which calib "healthy" answers are >= 99% right.
Re-scoring from the CSV is exact for a build with min_margin 0: an UNCERTAIN crop label then means p < its floor.

Usage (from ml/; CSVs as written by scripts/photo-eval.mjs or ml/reports/<version>/app_check_*.csv):
  python -m leaf.app_floors CALIB.csv [TEST.csv]
"""
import csv
import json
import sys

MIN_PROB = 0.40
GRID = [round(MIN_PROB + 0.01 * i, 2) for i in range(60)]
CROPS = {"coffee": ["coffee_healthy", "coffee_rust", "coffee_miner", "coffee_cercospora", "coffee_phoma"],
         "maize": ["maize_healthy", "maize_leaf_blight", "maize_leaf_spot", "maize_fall_armyworm", "maize_streak_virus"],
         "bean": ["bean_healthy", "bean_angular_leaf_spot", "bean_rust"]}


def load(path):
    return list(csv.DictReader(open(path, encoding="utf-8")))


def answered(row, floors) -> bool:
    return (row["status"] in ("CONFIDENT", "UNCERTAIN") and row["said"] not in ("", "other")
            and float(row["prob"]) >= floors.get(row["said"], MIN_PROB))


def choose(calib) -> dict:
    floors = {}
    for crop, labels in CROPS.items():
        said = [r for r in calib if r["said"] in labels]
        for f in GRID:
            a = [r for r in said if answered(r, {**floors, **{l: f for l in labels}})]
            if a and sum(r["said"] == r["label"] for r in a) / len(a) >= 0.90:
                if f > MIN_PROB:
                    floors.update({l: f for l in labels})
                break
        healthy = f"{crop}_healthy"
        for f in GRID:
            if f < floors.get(healthy, MIN_PROB):
                continue
            a = [r for r in calib if r["said"] == healthy and answered(r, {**floors, healthy: f})]
            if a and sum(r["label"] == healthy for r in a) / len(a) >= 0.99:
                if f > floors.get(healthy, MIN_PROB):
                    floors[healthy] = f
                break
    return floors


def report(name, rows, floors):
    print(name)
    for crop, labels in CROPS.items():
        c = [r for r in rows if r["label"] in labels]
        if not c:
            continue
        a = [r for r in c if answered(r, floors)]
        right = sum(r["said"] == r["label"] for r in a)
        sick_healthy = sum(r["said"].endswith("_healthy") and not r["label"].endswith("_healthy") for r in a)
        print(f"  {crop:6s} n {len(c):3d}  answered {len(a) / len(c):.1%}  right {right / max(len(a), 1):.1%}  "
              f"sick called healthy {sick_healthy}")
    other = [r for r in rows if r["label"] == "other"]
    if other:
        print(f"  other  n {len(other):3d}  answered {sum(answered(r, floors) for r in other)}")


if __name__ == "__main__":
    calib = load(sys.argv[1])
    floors = choose(calib)
    print("per_class_min_prob:", json.dumps(floors))
    report("calib", calib, floors)
    if len(sys.argv) > 2:
        report("test", load(sys.argv[2]), floors)
