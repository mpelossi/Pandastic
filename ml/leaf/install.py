"""Copy a trained model into the app (replacing the stub) and its reports into ml/reports/.

Usage (from ml/): python -m leaf.install artifacts/<version>
"""
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android/app/src/main/assets/models"
REPORTS = ROOT / "ml/reports"

if __name__ == "__main__":
    source = Path(sys.argv[1])
    meta = json.loads((source / "leaf_classifier.json").read_text(encoding="utf-8"))
    size = (source / "leaf_classifier.onnx").stat().st_size
    if meta.get("stub") or size > 15 * 1024 * 1024:
        sys.exit(f"refusing: stub={meta.get('stub')} size={size} (rule: classifier <= 15 MB)")
    shutil.copy2(source / "leaf_classifier.onnx", ASSETS / "leaf_classifier.onnx")
    shutil.copy2(source / "leaf_classifier.json", ASSETS / "leaf_classifier.json")
    target = REPORTS / meta["version"]
    if (source / "reports").exists():
        shutil.copytree(source / "reports", target, dirs_exist_ok=True)
    print(f"installed {meta['version']} ({size // 1024} KB) into {ASSETS}; reports in {target}")
    print(json.dumps(meta["eval"], indent=2))
