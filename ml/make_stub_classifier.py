"""Build a STUB leaf classifier with the app's exact inputs and outputs.

It is NOT a trained model. It averages the RGB colour of the photo and maps it to coffee labels
with a hand-set linear rule (green -> healthy, orange/yellow -> rust, brown -> cercospora,
grey/blue/white -> other), so the Android app can integrate ONNX Runtime and exercise every
resolver status before a trained model exists. The real model ships under the same file names.

Usage: python ml/make_stub_classifier.py
"""
import json
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper

OUT = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/models"
LABELS = ["coffee_healthy", "coffee_rust", "coffee_miner", "coffee_cercospora", "coffee_phoma", "other"]
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

# logits = W_raw^T @ [r, g, b] + b_raw, where r, g, b are mean channel values in 0..1.
W_RAW = np.array([
    # healthy  rust  miner  cercospora  phoma  other
    [-10.0,    9.0,  0.0,   6.0,        0.0,   -4.0],   # r
    [ 14.0,    4.0,  0.0,  -6.0,        0.0,   -4.0],   # g
    [ -6.0,   -9.0,  0.0,  -2.0,        0.0,    8.0],   # b
], dtype=np.float32)
B_RAW = np.array([-1.0, -3.0, -3.0, 0.3, -3.0, 0.4], dtype=np.float32)


def build() -> onnx.ModelProto:
    # The model sees normalised input x_n = (x - mean) / std, so fold the normalisation into the weights:
    # W_raw^T x = W_raw^T (x_n * std + mean)  =>  W_n = W_raw * std[:, None], b_n = b_raw + mean @ W_raw.
    w_n = (W_RAW * STD[:, None]).astype(np.float32)
    b_n = (B_RAW + MEAN @ W_RAW).astype(np.float32)
    nodes = [
        helper.make_node("ReduceMean", ["input"], ["channel_mean"], axes=[2, 3], keepdims=0),
        helper.make_node("MatMul", ["channel_mean", "W"], ["scores"]),
        helper.make_node("Add", ["scores", "B"], ["logits"]),
    ]
    graph = helper.make_graph(
        nodes, "leaf_classifier_stub",
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 3, 224, 224])],
        [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, len(LABELS)])],
        [helper.make_tensor("W", TensorProto.FLOAT, w_n.shape, w_n.flatten()),
         helper.make_tensor("B", TensorProto.FLOAT, b_n.shape, b_n.flatten())],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="pandastic-stub")
    model.ir_version = 8  # readable by onnxruntime-android 1.30
    model.doc_string = "STUB colour heuristic, not trained. Replace with a trained model (ml/leaf)."
    onnx.checker.check_model(model)
    return model


def metadata() -> dict:
    return {
        "version": "leaf-stub-0",
        "stub": True,
        "note": "STUB: mean-colour heuristic, NOT a trained model. For app integration only; never demo it as AI.",
        "arch": "stub_mean_colour_linear",
        "input_size": 224,
        "resize": "direct_bilinear",
        "mean": MEAN.tolist(),
        "std": STD.tolist(),
        "labels": LABELS,
        "temperature": 1.0,
        "thresholds": {"min_prob": 0.70, "min_margin": 0.25},
        "per_class_min_prob": {},
        "eval": {"test_set": "none (stub)", "n": 0, "accuracy": 0, "coverage_at_threshold": 0, "selective_accuracy": 0},
    }


def self_test(path: Path) -> None:
    import onnxruntime as ort
    session = ort.InferenceSession(str(path))
    colours = {"green leaf": (0.25, 0.55, 0.20), "orange rust": (0.85, 0.55, 0.15),
               "brown spot": (0.45, 0.30, 0.18), "grey wall": (0.55, 0.55, 0.58), "blue sky": (0.40, 0.60, 0.90)}
    for name, rgb in colours.items():
        x = np.ones((1, 3, 224, 224), dtype=np.float32) * np.array(rgb, dtype=np.float32)[None, :, None, None]
        x = (x - MEAN[None, :, None, None]) / STD[None, :, None, None]
        logits = session.run(["logits"], {"input": x})[0][0]
        p = np.exp(logits - logits.max()); p /= p.sum()
        order = np.argsort(-p)
        print(f"{name:12s} -> {LABELS[order[0]]:18s} p={p[order[0]]:.2f} margin={p[order[0]] - p[order[1]]:.2f}")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    onnx_path = OUT / "leaf_classifier.onnx"
    onnx.save(build(), onnx_path)
    (OUT / "leaf_classifier.json").write_text(json.dumps(metadata(), indent=2) + "\n", encoding="utf-8")
    print(f"wrote {onnx_path} ({onnx_path.stat().st_size} bytes) + leaf_classifier.json")
    self_test(onnx_path)
