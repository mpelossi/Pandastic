"""Modal app for the leaf classifier.

Run from ml/ (needs `modal token new` once):
  modal run modal_app.py --stage fetch                 # downloads + extracts all datasets into the Volume (CPU)
  modal run modal_app.py --stage manifest              # labels, pHash de-duplication, splits (CPU)
  modal run modal_app.py --stage cache                 # decode + shrink every image once, drop unreadable (CPU)
  modal run modal_app.py --stage train --epochs 12     # (cache, then) GPU training + calibration + ONNX export
  modal run modal_app.py --stage all                   # all of the above

MLOps: the run id is a hash of (manifest, hyper-parameters, training code), checkpoints are written to the
Volume after every epoch, and the train function retries; so a cancelled or preempted run resumes at its last
epoch when relaunched with the same arguments. Early stopping (patience 4). Lineage hashes go into the metadata.
  modal run --detach modal_app.py --stage all          # same, and it keeps running if this machine sleeps
  --labels p1 adds maize blight/grey leaf spot + bean classes; --labels p2 also healthy maize, fall armyworm and
  streak virus from CCMT (Ghana). --smoke runs a 30-step training to test the GPU path cheaply.
  --coffee-split mix (default): half of RoCoLe's plants (Ecuador, phone photos on the plant) are trained on, the
  others are coffee calib/test; xc: RoCoLe is never trained on (cross-country test). Manifests and run ids carry both, e.g. manifest-p2-xc.csv, leaf-p2-xc-1a2b3c4d.
  modal run modal_app.py --stage reeval --labels p1 --version leaf-p1-...   # adds eval.by_crop to an older model
  --input-size 320: train and export at a higher resolution (own image cache; the app reads input_size from the JSON)
  --seed 14: another member of the same recipe; then --stage ensemble --version leaf-...,leaf-...,leaf-... averages them

Then fetch the artifacts and install them into the app:
  modal volume get pandastic-models leaf/<version> ./artifacts/
  python -m leaf.install artifacts/<version>
"""
import json
from pathlib import Path

import modal

app = modal.App("pandastic-leaf")
image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("libarchive-tools")  # bsdtar: reads BRACOL's zip, which has no central directory
    .pip_install("torch==2.14.1", "torchvision==0.29.1", "timm==1.0.30", "onnx==1.23.1", "onnxruntime==1.30.0",
                 "pillow==12.3.0", "imagehash==4.3.2", "numpy==2.5.3", "requests")
    .add_local_python_source("leaf", ignore=["**/__pycache__", "**/*.pyc"])  # also ships leaf/ccmt_files.csv
)
data_volume = modal.Volume.from_name("pandastic-data", create_if_missing=True)
models_volume = modal.Volume.from_name("pandastic-models", create_if_missing=True)
DATA, MODELS = Path("/data"), Path("/models")


def _labels(name: str):
    from leaf import config
    return config.LABEL_SETS[name]


def _tag(labels: str, coffee_split: str) -> str:
    return f"{labels}-{coffee_split}"


def _cache_side(input_size: int) -> int:
    """Short side of the image cache: a 60%-area random crop must still cover the model input (320 for 224)."""
    return 320 if input_size <= 224 else 32 * -(-int(input_size / 0.6 ** 0.5) // 32)


def _cached_manifest(tag: str, input_size: int) -> Path:
    side = _cache_side(input_size)
    return DATA / (f"manifest-{tag}-cached.csv" if side == 320 else f"manifest-{tag}-cached{side}.csv")


@app.function(image=image, volumes={"/data": data_volume}, cpu=4, memory=8192, timeout=3 * 3600)
def fetch(sources: list[str]) -> None:
    from leaf import data
    data.download(sources, DATA / "raw", work_dir=DATA / "work")
    data_volume.commit()
    data.extract(sources, DATA / "raw", DATA / "work")
    data_volume.commit()


@app.function(image=image, volumes={"/data": data_volume}, cpu=16, memory=16384, timeout=2 * 3600)
def manifest(labels: str, coffee_split: str) -> dict:
    from leaf import data
    rows, stats = data.build_manifest(DATA / "work", _labels(labels), workers=16, coffee_split=coffee_split)
    tag = _tag(labels, coffee_split)
    data.write_manifest(rows, DATA / f"manifest-{tag}.csv")
    (DATA / f"manifest-{tag}.json").write_text(json.dumps(stats, indent=2))
    data_volume.commit()
    return stats


@app.function(image=image, volumes={"/data": data_volume}, cpu=16, memory=16384, timeout=2 * 3600)
def cache(tag: str, input_size: int = 224) -> dict:
    """Decode + shrink every image once (CPU), so GPU epochs read small JPEGs. Reused across runs and labels;
    one cache folder per short side, so a higher --input-size never reads images shrunk for 224."""
    from leaf import data
    data_volume.reload()
    side = _cache_side(input_size)
    rows, dropped = data.build_cache(data.read_manifest(DATA / f"manifest-{tag}.csv"),
                                     DATA / ("cache" if side == 320 else f"cache-{side}"), short_side=side, workers=16)
    data.write_manifest(rows, _cached_manifest(tag, input_size))
    data_volume.commit()
    return {"cached": len(rows), "dropped": dropped}


def _run_id(tag: str, manifest_bytes: bytes, hparams: dict) -> tuple[str, dict]:
    """Same data + hyper-parameters + training code -> same run id, so a restarted run resumes its checkpoint."""
    import hashlib
    from pathlib import Path as P
    import leaf.train
    code = P(leaf.train.__file__).read_bytes() + P(leaf.train.__file__).with_name("config.py").read_bytes()
    config_hash = hashlib.sha256(json.dumps(hparams, sort_keys=True).encode() + code).hexdigest()
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    run_id = f"leaf-{tag}-{hashlib.sha256((manifest_sha + config_hash).encode()).hexdigest()[:8]}"
    return run_id, {"manifest_sha256": manifest_sha, "config_sha256": config_hash}


# Retries + per-epoch checkpoints: a preempted or cancelled container resumes where it stopped.
@app.function(image=image, volumes={"/data": data_volume, "/models": models_volume}, gpu="L4", cpu=16,
              memory=32768, timeout=4 * 3600, retries=modal.Retries(max_retries=2, initial_delay=10.0))
def train(labels: str, coffee_split: str, epochs: int, batch_size: int, lr: float, smoke: bool, git_sha: str = "",
          input_size: int = 224, seed: int = 13, arch: str = "") -> dict:
    import timm
    import torch
    from leaf import config, data, train as trainer
    data_volume.reload()
    models_volume.reload()
    config.INPUT_SIZE = input_size  # read at call time by train.py (resize, ONNX export, leaf_classifier.json)
    config.ARCH = arch or config.ARCH  # another timm backbone; the run id changes with it (hparams["arch"])
    tag = _tag(labels, coffee_split)
    cached = _cached_manifest(tag, input_size)
    manifest_path = cached if cached.exists() else DATA / f"manifest-{tag}.csv"
    rows = data.read_manifest(manifest_path)
    stats = json.loads((DATA / f"manifest-{tag}.json").read_text())
    hparams = {"labels": labels, "coffee_split": coffee_split, "epochs": epochs, "batch_size": batch_size, "lr": lr, "smoke": smoke,
               "arch": config.ARCH, "seed": seed, "patience": 4}
    if arch:
        tag += "-" + arch.split(".")[0].replace("mobilenetv4_", "").replace("_", "")
    if seed != 13:  # another seed of the same recipe, e.g. an ensemble member
        tag += f"-s{seed}"
    if input_size != 224:  # 224 keeps the hyper-parameters (and run ids) of earlier runs
        hparams["input_size"] = input_size
        tag += f"-r{input_size}"
    version, hashes = _run_id(tag, manifest_path.read_bytes(), hparams)
    version += "-smoke" if smoke else ""
    lineage = {**hashes, "manifest": manifest_path.name, "git_sha": git_sha, "seed": seed,
               "torch": str(torch.__version__), "timm": str(timm.__version__)}
    print(f"run {version} (resumes automatically if checkpoints exist), lineage {lineage}")
    meta = trainer.run(rows, _labels(labels), MODELS / "leaf" / version, stats, epochs=1 if smoke else epochs,
                       batch_size=batch_size, lr=lr, device="cuda", workers=14, max_steps=30 if smoke else None,
                       version=version, on_checkpoint=models_volume.commit, lineage=lineage, seed=seed)
    models_volume.commit()
    # Plain JSON types only: the laptop that receives this has no torch/numpy to unpickle their objects.
    return json.loads(json.dumps(meta, default=str))


@app.function(image=image, volumes={"/data": data_volume, "/models": models_volume}, cpu=16, memory=16384,
              timeout=3600)
def reeval(labels: str, coffee_split: str, version: str) -> dict:
    """Adds the per-crop breakdown to a model trained before it existed (CPU; a few minutes)."""
    from leaf import data, train as trainer
    tag = _tag(labels, coffee_split)
    cached = DATA / f"manifest-{tag}-cached.csv"  # readable, pre-resized images when the cache stage has run
    rows = data.read_manifest(cached if cached.exists() else DATA / f"manifest-{tag}.csv")
    meta = trainer.reevaluate(rows, _labels(labels), MODELS / "leaf" / version, device="cpu", workers=14)
    models_volume.commit()
    return meta


@app.function(image=image, volumes={"/data": data_volume, "/models": models_volume}, gpu="L4", cpu=16, memory=32768,
              timeout=3600)
def ensemble(labels: str, coffee_split: str, members: list[str], input_size: int = 224, git_sha: str = "") -> dict:
    """Average trained members (same recipe, other seeds) and calibrate/evaluate/export it like one model."""
    import hashlib
    from leaf import config, data, train as trainer
    data_volume.reload()
    models_volume.reload()
    config.INPUT_SIZE = input_size
    tag = _tag(labels, coffee_split)
    cached = _cached_manifest(tag, input_size)
    rows = data.read_manifest(cached if cached.exists() else DATA / f"manifest-{tag}.csv")
    stats = json.loads((DATA / f"manifest-{tag}.json").read_text())
    arch = json.loads((MODELS / "leaf" / members[0] / "leaf_classifier.json").read_text())["arch"]
    if arch != config.ARCH:  # members trained with --arch: rebuild them with their own backbone
        tag += "-" + arch.split(".")[0].replace("mobilenetv4_", "").replace("_", "")
        config.ARCH = arch
    version = f"leaf-{tag}-ens{len(members)}-" + hashlib.sha256(",".join(members).encode()).hexdigest()[:8]
    meta = trainer.ensemble([MODELS / "leaf" / m for m in members], rows, _labels(labels), MODELS / "leaf" / version,
                            stats, device="cuda", workers=14, version=version,
                            lineage={"members": members, "git_sha": git_sha, "manifest": cached.name})
    models_volume.commit()
    return json.loads(json.dumps(meta, default=str))


@app.function(image=image, volumes={"/data": data_volume}, cpu=16, memory=16384, timeout=3600)
def photo_stats(tag: str, splits: list[str]) -> dict:
    """QualityGate numbers (plant share, sharpness, luminance) of real dataset photos through the app path."""
    from concurrent.futures import ProcessPoolExecutor
    from leaf import data, photo_stats as ps
    data_volume.reload()
    rows = [r for r in data.read_manifest(DATA / f"manifest-{tag}.csv") if r["split"] in splits]
    with ProcessPoolExecutor(16) as pool:
        values = list(pool.map(ps.measure, [r["path"] for r in rows], chunksize=16))
    return ps.summarise(rows, values)


@app.function(image=image, volumes={"/data": data_volume, "/models": models_volume}, cpu=16, memory=16384,
              timeout=3600)
def probs(labels: str, coffee_split: str, version: str, splits: list[str]) -> dict:
    """The exported ONNX's calibrated probabilities on held-out splits (same preprocessing as eval), so per-label
    minimums can be studied on the laptop without retraining (leaf/label_floors.py)."""
    import numpy as np
    import onnxruntime as ort
    from concurrent.futures import ThreadPoolExecutor
    from leaf import data, train as trainer
    data_volume.reload()
    models_volume.reload()
    tag = _tag(labels, coffee_split)
    cached = _cached_manifest(tag, 224)
    rows = [r for r in data.read_manifest(cached if cached.exists() else DATA / f"manifest-{tag}.csv")
            if r["split"] in splits]
    model_dir = MODELS / "leaf" / version
    meta = json.loads((model_dir / "leaf_classifier.json").read_text())
    options = ort.SessionOptions()
    options.add_session_config_entry("session.disable_quant_qdq", "1")
    sess = ort.InferenceSession(str(model_dir / "leaf_classifier.onnx"), options)
    one = lambda r: sess.run(None, {"input": trainer.to_tensor(trainer.load_rgb(r["path"])).numpy()[None]})[0][0]
    with ThreadPoolExecutor(16) as pool:  # the exported graph takes one photo at a time, like the app
        logits = np.stack(list(pool.map(one, rows)))
    z = logits / meta["temperature"]
    p = np.exp(z - z.max(1, keepdims=True))
    p /= p.sum(1, keepdims=True)
    return {"labels": meta["labels"], "probs": p.round(5).tolist(), "y": [meta["labels"].index(r["label"]) for r in rows],
            "split": [r["split"] for r in rows], "source": [r["source"] for r in rows],
            "group": [r.get("group", "") for r in rows], "path": [r["path"] for r in rows]}


@app.function(image=image, cpu=8, memory=8192, timeout=1800)
def smoke_test() -> str:
    """leaf/smoke.py for machines without torch: `modal run modal_app.py::smoke_test` (synthetic data, CPU)."""
    import subprocess
    r = subprocess.run(["python", "-m", "leaf.smoke"], cwd="/root", capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(r.stdout[-2000:] + r.stderr[-4000:])
    print(r.stdout[-2000:])
    return r.stdout[-2000:]


SOURCES = ["bracol", "jmuben", "jmuben2", "rocole", "plantdoc", "ibean", "ccmt", "caltech101"]


@app.function(image=image, timeout=8 * 3600)
def pipeline(stage: str, labels: str, coffee_split: str, epochs: int, batch_size: int, lr: float, smoke: bool,
             git_sha: str = "", input_size: int = 224, seed: int = 13, arch: str = "") -> dict:
    """Runs the stages from Modal, not from the laptop, so a detached run finishes even if the laptop sleeps."""
    result = {}
    if stage in ("fetch", "all"):
        fetch.remote(SOURCES)
    if stage in ("manifest", "all"):
        result["manifest"] = manifest.remote(labels, coffee_split)
    if stage in ("cache", "train", "all"):  # idempotent: only new images are decoded
        result["cache"] = cache.remote(_tag(labels, coffee_split), input_size)
    if stage in ("train", "all"):
        result["meta"] = train.remote(labels, coffee_split, epochs, batch_size, lr, smoke, git_sha, input_size, seed, arch)
    return result


@app.local_entrypoint()
def main(stage: str = "all", labels: str = "p0", coffee_split: str = "mix", epochs: int = 12, batch_size: int = 64,
         lr: float = 1e-3, smoke: bool = False, version: str = "", input_size: int = 224, seed: int = 13,
         arch: str = ""):
    import subprocess
    from leaf import config
    if stage == "reeval":
        print(json.dumps(reeval.remote(labels, coffee_split, version)["eval"], indent=2))
        return
    if stage == "ensemble":  # --version member1,member2,member3
        git_sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
        meta = ensemble.remote(labels, coffee_split, version.split(","), input_size, git_sha)
        print(json.dumps(meta["eval"], indent=2))
        print(f"\nmodal volume get pandastic-models leaf/{meta['version']} ./artifacts/")
        return
    if stage == "probs":  # --version <model>: calib + test probabilities -> artifacts/<model>/probs.json
        out = Path(__file__).parent / "artifacts" / version / "probs.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(probs.remote(labels, coffee_split, version, ["calib", "test"])))
        print(f"wrote {out}")
        return
    if stage == "photo-stats":
        print(json.dumps(photo_stats.remote(_tag(labels, coffee_split), ["calib", "test"]), indent=1))
        return
    git_sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    result = pipeline.remote(stage, labels, coffee_split, epochs, batch_size, lr, smoke, git_sha, input_size, seed, arch)
    if "cache" in result:
        print(f"cache: {result['cache']['cached']} images, dropped {len(result['cache']['dropped'])}")
    if stage in ("fetch", "all"):
        print("fetched + extracted:", ", ".join(f"{s} ({config.SOURCES[s]['licence']})" for s in SOURCES))
    if "manifest" in result:
        print(json.dumps(result["manifest"], indent=2))
    if "meta" in result:
        meta = result["meta"]
        print(json.dumps(meta["eval"], indent=2))
        print(f"\nmodal volume get pandastic-models leaf/{meta['version']} ./artifacts/\n"
              f"python -m leaf.install artifacts/{meta['version']}")
