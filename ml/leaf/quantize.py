"""Static int8 (QDQ) quantization of a trained leaf classifier, calibrated on RoCoLe calib-plant photos, and a
before/after check on the RoCoLe test plants through the app's photo path (ml/leaf/class_thresholds.py).

int8 makes the file ~4x smaller and usually faster on phone CPUs. It is only worth shipping if accuracy holds, so
this prints both models' numbers side by side and writes the int8 model next to the original, never over it.

Usage (from ml/): python -m leaf.quantize <model_dir>   ->  <model_dir>/leaf_classifier.int8.onnx

Result for leaf-p2-mix-eff20277 (2026-10-04): 10.0 -> 2.8 MB and 2.4 -> 1.4 ms per photo on a laptop, but top-1 on the
RoCoLe test plants 0.925 -> 0.892 and coverage 0.77 -> 0.58 (confidences shift). Not shipped: fp32 already fits
the 15 MB budget and runs in 100-250 ms on the emulator.
"""
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static
from onnxruntime.quantization.shape_inference import quant_pre_process

from . import class_thresholds as ct


class Photos(CalibrationDataReader):
    def __init__(self, arrays):
        self.items = iter([{"input": a[None]} for a in arrays])

    def get_next(self):
        return next(self.items, None)


def score(model_path: Path, meta: dict, arrays, truth, labels) -> dict:
    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    probs, started = [], time.perf_counter()
    for a in arrays:
        z = sess.run(None, {"input": a[None]})[0][0] / meta["temperature"]
        e = np.exp(z - z.max())
        probs.append(e / e.sum())
    ms = (time.perf_counter() - started) / len(arrays) * 1000
    probs = np.array(probs)
    top, answered = ct.decide(probs, labels, meta, meta.get("per_class_min_prob") or {})
    healthy = labels.index("coffee_healthy")
    return {"size_mb": round(model_path.stat().st_size / 1e6, 1), "ms_per_photo_laptop": round(ms, 1),
            "top1": round(float((top == truth).mean()), 4), "coverage": round(float(answered.mean()), 4),
            "selective": round(float((top[answered] == truth[answered]).mean()), 4),
            "rust_called_healthy": int(((top == healthy) & answered & (truth != healthy)).sum()), "probs": probs}


def main():
    model_dir = Path(sys.argv[1])
    meta = json.loads((model_dir / "leaf_classifier.json").read_text(encoding="utf-8"))
    labels, size = meta["labels"], meta["input_size"]
    mean, std = np.array(meta["mean"], np.float32), np.array(meta["std"], np.float32)
    rows = ct.rocole_split(meta.get("training", {}).get("coffee_split") or "mix")
    arrays = {s: [ct.app_input(ct.fetch(r), size, mean, std) for r in rows if r["split"] == s] for s in ("calib", "test")}
    truth = np.array([labels.index(r["label"]) for r in rows if r["split"] == "test"])

    fp32, prepped, int8 = model_dir / "leaf_classifier.onnx", model_dir / "leaf_classifier.pre.onnx", model_dir / "leaf_classifier.int8.onnx"
    quant_pre_process(str(fp32), str(prepped))
    quantize_static(str(prepped), str(int8), Photos(arrays["calib"]), quant_format=QuantFormat.QDQ,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8, per_channel=True)
    prepped.unlink()
    a, b = score(fp32, meta, arrays["test"], truth, labels), score(int8, meta, arrays["test"], truth, labels)
    agree = float((a["probs"].argmax(1) == b["probs"].argmax(1)).mean())
    for tag, r in (("fp32", a), ("int8", b)):
        print(f"{tag}: " + json.dumps({k: v for k, v in r.items() if k != "probs"}))
    print(f"top-1 agreement fp32 vs int8 on {len(truth)} test photos: {agree:.3f}; "
          f"max |prob diff| {np.abs(a['probs'] - b['probs']).max():.3f}")


if __name__ == "__main__":
    main()
