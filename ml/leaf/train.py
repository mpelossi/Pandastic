"""Train, calibrate, pick thresholds, export ONNX + leaf_classifier.json + reports (contracts section 1)."""
import csv
import json
import math
import os
import random
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image, ImageFilter, ImageOps, ImageFile
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

from . import config


# ---------------------------------------------------------------- data

# A few dataset JPEGs (BRACOL's zip is itself truncated) end a few bytes early; decode what is there
# instead of crashing a DataLoader worker mid-epoch.
ImageFile.LOAD_TRUNCATED_IMAGES = True


def load_rgb(path: str) -> Image.Image:
    with Image.open(path) as im:
        return ImageOps.exif_transpose(im).convert("RGB")


def to_tensor(im: Image.Image) -> torch.Tensor:
    """Contract preprocessing: direct bilinear resize to 224 (no crop), /255, ImageNet mean/std, CHW."""
    im = im.resize((config.INPUT_SIZE, config.INPUT_SIZE), Image.BILINEAR)
    x = torch.from_numpy(np.asarray(im, dtype=np.float32) / 255.0).permute(2, 0, 1)
    mean = torch.tensor(config.MEAN).view(3, 1, 1)
    std = torch.tensor(config.STD).view(3, 1, 1)
    return (x - mean) / std


def replace_white_background(im: Image.Image, background: Image.Image) -> Image.Image:
    """BRACOL leaves are shot on white paper; paste them on a field-like background so 'white = coffee' isn't learned."""
    a = np.asarray(im, dtype=np.int16)
    white = (a.min(axis=2) > 185) & ((a.max(axis=2) - a.min(axis=2)) < 30)
    if white.mean() < 0.15:
        return im
    mask = Image.fromarray(((~white) * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(2))
    return Image.composite(im, background.resize(im.size), mask)


class LeafDataset(Dataset):
    def __init__(self, rows, labels, train: bool, backgrounds=None, seed: int = 0):
        self.rows = rows
        self.index = {label: i for i, label in enumerate(labels)}
        self.train = train
        self.backgrounds = backgrounds or []
        self.rng = random.Random(seed)

    def __len__(self):
        return len(self.rows)

    def _augment(self, im: Image.Image, source: str) -> Image.Image:
        rng = self.rng
        if source == "bracol" and self.backgrounds and rng.random() < 0.8:
            im = replace_white_background(im, load_rgb(rng.choice(self.backgrounds)))
        w, h = im.size  # random crop of 60-100% area, then flips/rotations/colour, as field photos vary
        scale = rng.uniform(0.6, 1.0)
        cw, ch = int(w * math.sqrt(scale)), int(h * math.sqrt(scale))
        x0, y0 = rng.randint(0, max(0, w - cw)), rng.randint(0, max(0, h - ch))
        im = im.crop((x0, y0, x0 + cw, y0 + ch))
        if rng.random() < 0.5:
            im = ImageOps.mirror(im)
        im = im.rotate(rng.choice([0, 90, 180, 270]) + rng.uniform(-15, 15), expand=False, fillcolor=(90, 110, 70))
        from PIL import ImageEnhance
        for enhancer in (ImageEnhance.Brightness, ImageEnhance.Contrast, ImageEnhance.Color):
            im = enhancer(im).enhance(rng.uniform(0.7, 1.3))
        if rng.random() < 0.2:
            im = im.filter(ImageFilter.GaussianBlur(rng.uniform(0.5, 1.5)))
        return im

    def __getitem__(self, i):
        r = self.rows[i]
        im = load_rgb(r["path"])
        if self.train:
            im = self._augment(im, r["source"])
        return to_tensor(im), self.index[r["label"]]


# ---------------------------------------------------------------- model + training

def _seed_worker(_):
    """Each DataLoader worker gets a copy of the dataset's RNG; reseed it so workers (and epochs) augment differently."""
    info = torch.utils.data.get_worker_info()
    info.dataset.rng = random.Random(torch.initial_seed() % 2**32)


def create_model(num_classes: int, pretrained: bool):
    import timm
    return timm.create_model(config.ARCH, pretrained=pretrained, num_classes=num_classes)


def loader_options(device: str, workers: int) -> dict:
    """Keep the GPU fed: pinned memory, workers that survive across epochs, a few batches prefetched."""
    options = {"num_workers": workers, "pin_memory": device == "cuda"}
    if workers > 0:
        options.update(persistent_workers=True, prefetch_factor=4)
    return options


def to_device(x: torch.Tensor, device: str) -> torch.Tensor:
    if device == "cuda":  # channels_last is faster for convolutions on tensor cores
        return x.to(device, non_blocking=True, memory_format=torch.channels_last)
    return x.to(device)


@torch.no_grad()
def predict_logits(model, rows, labels, device, batch_size=128, workers=4):
    model.eval()
    options = loader_options(device, workers)
    options.pop("persistent_workers", None)  # one pass only
    loader = DataLoader(LeafDataset(rows, labels, train=False), batch_size=batch_size, **options)
    out, ys = [], []
    for x, y in loader:
        out.append(model(to_device(x, device)).float().cpu())
        ys.append(y)
    if not out:
        return torch.zeros(0, len(labels)), torch.zeros(0, dtype=torch.long)
    return torch.cat(out), torch.cat(ys)


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def save_atomic(obj, path: Path) -> None:
    """Write to a temp file, then rename: a run killed mid-save never leaves a corrupt checkpoint."""
    tmp = path.with_suffix(path.suffix + ".tmp")
    torch.save(obj, tmp)
    os.replace(tmp, path)


def train_model(rows, labels, epochs=12, batch_size=64, lr=1e-3, pretrained=True, device="cpu",
                workers=4, max_steps=None, log=print, seed=13, ckpt_dir: Path = None, on_checkpoint=None,
                patience: int = 4):
    """Train with per-epoch checkpoints. If ckpt_dir holds last.pt from an interrupted run, resume from it.

    on_checkpoint() is called after each checkpoint (on Modal: commit the Volume so it survives the container).
    Early stopping: stop after `patience` epochs without a better validation accuracy.
    """
    seed_everything(seed)
    train_rows = [r for r in rows if r["split"] == "train"]
    val_rows = [r for r in rows if r["split"] == "val"]
    backgrounds = [r["path"] for r in train_rows if r["label"] == "other"][:2000]
    dataset = LeafDataset(train_rows, labels, train=True, backgrounds=backgrounds, seed=seed)
    counts = np.bincount([dataset.index[r["label"]] for r in train_rows], minlength=len(labels))
    weights = [1.0 / max(1, counts[dataset.index[r["label"]]]) for r in train_rows]  # class-balanced sampling
    sampler = WeightedRandomSampler(weights, num_samples=len(train_rows), replacement=True)
    loader = DataLoader(dataset, batch_size=batch_size, sampler=sampler, drop_last=len(train_rows) > batch_size,
                        worker_init_fn=_seed_worker, **loader_options(device, workers))

    model = create_model(len(labels), pretrained).to(device)
    if device == "cuda":
        model = model.to(memory_format=torch.channels_last)
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=0.05)
    steps = epochs * max(1, len(loader))
    if max_steps:
        steps = min(steps, max_steps)
    warmup = max(1, int(0.05 * steps))  # linear warmup, then cosine decay; safe for any number of steps
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, lambda s: (s + 1) / warmup if s < warmup
                                                  else 0.5 * (1 + math.cos(math.pi * (s - warmup) / max(1, steps - warmup))))
    use_amp = device == "cuda"
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)
    best_state, best_val, step, history, start_epoch, stale = None, -1.0, 0, [], 0, 0
    last = ckpt_dir / "last.pt" if ckpt_dir else None
    if last and last.exists():
        ck = torch.load(last, map_location="cpu", weights_only=False)
        model.load_state_dict(ck["model"])
        optimizer.load_state_dict(ck["optimizer"])
        scheduler.load_state_dict(ck["scheduler"])
        scaler.load_state_dict(ck["scaler"])
        best_state, best_val, step, history = ck["best_state"], ck["best_val"], ck["step"], ck["history"]
        start_epoch, stale = ck["epoch"], ck.get("stale", 0)
        torch.set_rng_state(ck["torch_rng"])
        log(f"resumed from {last} after epoch {start_epoch} (best val_acc {best_val:.4f})")
    for epoch in range(start_epoch, epochs):
        if stale >= patience:
            log(f"early stop: no val_acc gain for {patience} epochs (best {best_val:.4f})")
            break
        model.train()
        started, total, seen = time.time(), 0.0, 0
        for x, y in loader:
            x, y = to_device(x, device), y.to(device, non_blocking=True)
            with torch.autocast(device_type="cuda", dtype=torch.float16, enabled=use_amp):
                loss = F.cross_entropy(model(x), y, label_smoothing=0.1)
            optimizer.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            scheduler.step()
            total += loss.item() * len(y)
            seen += len(y)
            step += 1
            if max_steps and step >= max_steps:
                break
        logits, y_val = predict_logits(model, val_rows, labels, device, workers=workers)
        val_acc = (logits.argmax(1) == y_val).float().mean().item() if len(y_val) else 0.0
        history.append({"epoch": epoch + 1, "train_loss": total / max(1, seen), "val_acc": val_acc, "seconds": time.time() - started})
        log(f"epoch {epoch + 1}/{epochs} loss {total / max(1, seen):.4f} val_acc {val_acc:.4f}")
        if val_acc > best_val:
            best_val, best_state, stale = val_acc, {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}, 0
        else:
            stale += 1
        if last:
            save_atomic({"epoch": epoch + 1, "step": step, "model": model.state_dict(), "optimizer": optimizer.state_dict(),
                         "scheduler": scheduler.state_dict(), "scaler": scaler.state_dict(), "best_state": best_state,
                         "best_val": best_val, "history": history, "stale": stale, "torch_rng": torch.get_rng_state()}, last)
            if on_checkpoint:
                on_checkpoint()
        if max_steps and step >= max_steps:
            break
    model.load_state_dict(best_state)
    return model.to(memory_format=torch.contiguous_format), history


# ---------------------------------------------------------------- calibration + thresholds

def fit_temperature(logits: torch.Tensor, y: torch.Tensor) -> float:
    """Temperature scaling (Guo et al. 2017) on the validation split."""
    if len(y) == 0:
        return 1.0
    log_t = torch.zeros(1, requires_grad=True)
    optimizer = torch.optim.LBFGS([log_t], lr=0.1, max_iter=200)

    def closure():
        optimizer.zero_grad()
        loss = F.cross_entropy(logits / log_t.exp(), y)
        loss.backward()
        return loss

    optimizer.step(closure)
    return float(log_t.exp().clamp(0.05, 20).item())


def answered_mask(probs: np.ndarray, labels, min_prob: float, min_margin: float, per_class: dict = None):
    """Resolver: answer only if top1 != other, p1 >= its label's minimum (per_class or min_prob), margin >= min_margin."""
    other = labels.index("other") if "other" in labels else -1
    order = np.argsort(-probs, axis=1)
    top, second = order[:, 0], order[:, 1]
    p1 = probs[np.arange(len(probs)), top]
    margin = p1 - probs[np.arange(len(probs)), second]
    floor = np.array([(per_class or {}).get(labels[t], min_prob) for t in top]) if len(top) else np.zeros(0)
    return top, (top != other) & (p1 >= floor) & (margin >= min_margin)


def selective_metrics(probs: np.ndarray, y: np.ndarray, labels, min_prob: float, min_margin: float,
                      per_class: dict = None) -> dict:
    """What the app's resolver would do (answered_mask), scored against the labels."""
    other = labels.index("other") if "other" in labels else -1
    top, answered = answered_mask(probs, labels, min_prob, min_margin, per_class)
    is_plant = y != other
    plant_answered = answered & is_plant
    correct = plant_answered & (top == y)
    return {
        "coverage": float(plant_answered.sum() / max(1, is_plant.sum())),
        "selective_accuracy": float(correct.sum() / max(1, plant_answered.sum())),
        "other_false_accept": float((answered & ~is_plant).sum() / max(1, (~is_plant).sum())),
        "answered": int(plant_answered.sum()), "n_plant": int(is_plant.sum()), "n_other": int((~is_plant).sum()),
    }


def by_crop(probs: np.ndarray, y: np.ndarray, labels, min_prob: float, min_margin: float, per_class: dict = None) -> dict:
    """Selective metrics per crop, each slice with the held-out `other` photos. The coffee slice is the one to
    compare with a coffee-only (p0) model; by_source separates its cross-country part (RoCoLe) from the rest."""
    out = {}
    for crop in dict.fromkeys(label.split("_")[0] for label in labels if label != "other"):
        keep = np.array([labels[t] == "other" or labels[t].startswith(crop + "_") for t in y], dtype=bool)
        m = selective_metrics(probs[keep], y[keep], labels, min_prob, min_margin, per_class)
        out[crop] = {"n_plant": m["n_plant"], "accuracy": round(float((probs[keep].argmax(1) == y[keep]).mean()), 4),
                     "coverage_at_threshold": round(m["coverage"], 4),
                     "selective_accuracy": round(m["selective_accuracy"], 4)}
    return out


def by_source(probs: np.ndarray, y: np.ndarray, sources, labels, min_prob: float, min_margin: float,
              per_class: dict = None) -> dict:
    """Selective metrics per photo source, each slice with the held-out `other` photos. Says which numbers are
    cross-country (a source never trained on, e.g. RoCoLe) and which are same-source (held-out images)."""
    sources = np.asarray(sources)
    other = labels.index("other") if "other" in labels else -1
    out = {}
    for src in sorted(set(sources[y != other].tolist())):
        keep = (y == other) | (sources == src)
        m = selective_metrics(probs[keep], y[keep], labels, min_prob, min_margin, per_class)
        out[src] = {"n_plant": m["n_plant"], "accuracy": round(float((probs[keep].argmax(1) == y[keep]).mean()), 4),
                    "coverage_at_threshold": round(m["coverage"], 4),
                    "selective_accuracy": round(m["selective_accuracy"], 4)}
    return out


SOURCE_NAMES = {"bracol": "BRACOL (Brazil)", "jmuben": "JMuBEN (Kenya, lesion close-ups)",
                "rocole": "RoCoLe (Ecuador, smartphone photos on the plant)", "ccmt": "CCMT (Ghana)",
                "plantdoc": "PlantDoc", "ibean": "iBean (Uganda)", "caltech101": "Caltech-101 (objects, animals, scenes)"}


def describe_sets(rows) -> tuple[str, str]:
    """Plain-language test/train descriptions from what the manifest holds: which test sources were trained on."""
    def plant_sources(split):
        return {r["source"] for r in rows if r["split"] == split and r["label"] != "other"}
    trained, tested = plant_sources("train"), plant_sources("test")
    name = lambda src: SOURCE_NAMES.get(src, src)
    cross = [name(s) for s in sorted(tested - trained)]
    same = [name(s) for s in sorted(tested & trained)]
    parts = []
    if cross:
        parts.append(f"never trained on: {', '.join(cross)}")
    if same:
        parts.append(f"held-out images from training sources: {', '.join(same)}")
    test_set = "; ".join(parts) + "; + held-out `other`"
    train_set = " + ".join(name(s) for s in sorted(trained)) + "; PlantDoc/iBean other classes as `other`"
    return test_set, train_set


def reevaluate(rows, labels, out_dir: Path, device=None, workers=4, log=print) -> dict:
    """Re-score a trained model (model_best.pt + leaf_classifier.json) on its test split and add the per-crop
    breakdown to leaf_classifier.json and reports/metrics.json, without retraining."""
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    rows = drop_unreadable(rows, log)
    meta_path, metrics_path = out_dir / "leaf_classifier.json", out_dir / "reports/metrics.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    assert meta["labels"] == list(labels), (meta["labels"], labels)
    model = create_model(len(labels), pretrained=False)
    model.load_state_dict(torch.load(out_dir / "model_best.pt", map_location="cpu"))
    model.to(device)
    test_rows = [r for r in rows if r["split"] == "test"]
    logits, y = predict_logits(model, test_rows, labels, device, workers=workers)
    probs, y = F.softmax(logits / meta["temperature"], dim=1).numpy(), y.numpy()
    th = meta["thresholds"]
    meta["eval"]["test_set"] = describe_sets(rows)[0]
    meta["eval"]["by_crop"] = by_crop(probs, y, labels, th["min_prob"], th["min_margin"])
    meta["eval"]["by_source"] = by_source(probs, y, [r["source"] for r in test_rows], labels, th["min_prob"], th["min_margin"])
    meta_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    if metrics_path.exists():
        metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
        metrics["eval"] = meta["eval"]
        metrics_path.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    log(json.dumps(meta["eval"], indent=2))
    return meta


def choose_class_floors(probs, y, labels, th: dict, precision=0.95, min_answered=5) -> dict:
    """A "healthy" answer when the leaf is sick is the most harmful mistake (the farmer does nothing), so every
    *_healthy label gets the lowest minimum probability at which its calib precision reaches `precision`."""
    floors = {}
    for label in labels:
        if not label.endswith("_healthy"):
            continue
        k = labels.index(label)
        for floor in np.arange(th["min_prob"], 1.0, 0.01):
            top, answered = answered_mask(probs, labels, th["min_prob"], th["min_margin"], {**floors, label: float(floor)})
            said = answered & (top == k)
            # Stop at the first floor that is precise enough, or where the label is (almost) never said any more;
            # either way that floor is the safe one.
            if said.sum() < min_answered or (y[said] == k).mean() >= precision:
                if floor > th["min_prob"]:
                    floors[label] = round(float(floor), 2)
                break
        else:
            floors[label] = 0.99
    return floors


def choose_thresholds(probs, y, labels, target_accuracy=0.90, max_other_false_accept=0.05):
    """Highest coverage that meets the accuracy target on the calib split; otherwise the most accurate setting."""
    best, fallback = None, None
    for min_prob in np.arange(0.40, 0.96, 0.025):
        for min_margin in np.arange(0.0, 0.61, 0.05):
            m = selective_metrics(probs, y, labels, float(min_prob), float(min_margin))
            m.update(min_prob=round(float(min_prob), 3), min_margin=round(float(min_margin), 3))
            ok = m["selective_accuracy"] >= target_accuracy and m["other_false_accept"] <= max_other_false_accept
            if ok and (best is None or m["coverage"] > best["coverage"]):
                best = m
            if m["coverage"] >= 0.10 and (fallback is None or m["selective_accuracy"] > fallback["selective_accuracy"]):
                fallback = m
    chosen = best or fallback or {"min_prob": 0.7, "min_margin": 0.25}
    chosen["met_target"] = best is not None
    return chosen


# ---------------------------------------------------------------- export

def export_onnx(model, path: Path) -> None:
    model = model.cpu().eval()
    dummy = torch.randn(1, 3, config.INPUT_SIZE, config.INPUT_SIZE)
    kwargs = dict(input_names=["input"], output_names=["logits"], opset_version=17, dynamic_axes=None)
    try:
        torch.onnx.export(model, dummy, str(path), dynamo=False, **kwargs)
    except TypeError:  # older torch without the dynamo flag
        torch.onnx.export(model, dummy, str(path), **kwargs)
    import onnx
    onnx.checker.check_model(onnx.load(str(path)))


def onnx_parity(model, path: Path, samples: torch.Tensor) -> float:
    import onnxruntime as ort
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    with torch.no_grad():
        expected = model.cpu().eval()(samples).numpy()
    got = np.concatenate([session.run(["logits"], {"input": samples[i:i + 1].numpy()})[0] for i in range(len(samples))])
    return float(np.abs(expected - got).max())


# ---------------------------------------------------------------- full run

def readable(path: str) -> bool:
    try:
        with Image.open(path) as im:
            im.verify()
        return True
    except Exception:
        return False


def drop_unreadable(rows, log=print, workers=32):
    """Some dataset files are not images at all (e.g. JMuBEN2 healthy/2 (691).jpg); one crashed evaluation
    after 12 epochs. Check every file once, before training."""
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(workers) as pool:
        ok = list(pool.map(readable, [r["path"] for r in rows]))
    bad = [r["path"] for r, good in zip(rows, ok) if not good]
    if bad:
        log(f"dropped {len(bad)} unreadable files, e.g. {bad[:3]}")
    return [r for r, good in zip(rows, ok) if good]


def run(rows, labels, out_dir: Path, manifest_stats: dict, epochs=12, batch_size=64, lr=1e-3, pretrained=True,
        device=None, workers=4, max_steps=None, target_accuracy=0.90, version=None, log=print,
        on_checkpoint=None, lineage: dict = None, patience: int = 4, seed: int = 13) -> dict:
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = drop_unreadable(rows, log)
    version = version or "leaf-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    log(f"{version}: {sum(r['split'] == 'train' for r in rows)} train rows on {device}, labels {labels}")

    ckpt_dir = out_dir / "checkpoints"
    ckpt_dir.mkdir(exist_ok=True)
    model, history = train_model(rows, labels, epochs, batch_size, lr, pretrained, device, workers, max_steps, log,
                                 seed=seed, ckpt_dir=ckpt_dir, on_checkpoint=on_checkpoint, patience=patience)
    save_atomic(model.state_dict(), out_dir / "model_best.pt")  # survives a crash in calibration or export
    if on_checkpoint:
        on_checkpoint()
    training = {"epochs_run": len(history), "pretrained": pretrained, "epochs_max": epochs, "batch_size": batch_size,
                "lr": lr, "early_stop_patience": patience}
    return finish(model, rows, labels, out_dir, manifest_stats, history, version, device, workers, target_accuracy,
                  log, training, lineage)


def finish(model, rows, labels, out_dir: Path, manifest_stats: dict, history: list, version: str, device: str,
           workers: int, target_accuracy: float, log, training: dict, lineage: dict = None, arch: str = None) -> dict:
    """Calibrate (temperature on val, thresholds on calib), evaluate on test, export ONNX + leaf_classifier.json +
    reports. Shared by a trained model (run) and an averaged one (ensemble)."""
    model = model.to(device)
    split = lambda name: [r for r in rows if r["split"] == name]
    val_logits, val_y = predict_logits(model, split("val"), labels, device, workers=workers)
    temperature = fit_temperature(val_logits, val_y)
    log(f"temperature {temperature:.3f}")

    def probs_of(name):
        logits, y = predict_logits(model, split(name), labels, device, workers=workers)
        return F.softmax(logits / temperature, dim=1).numpy(), y.numpy()

    calib_p, calib_y = probs_of("calib")
    test_p, test_y = probs_of("test")
    th = choose_thresholds(calib_p, calib_y, labels, target_accuracy) if len(calib_y) else {"min_prob": 0.7, "min_margin": 0.25, "met_target": False}
    floors = choose_class_floors(calib_p, calib_y, labels, th) if len(calib_y) else {}
    log(f"per-class minimum probability (calib precision >= 0.95): {floors}")
    test_m = selective_metrics(test_p, test_y, labels, th["min_prob"], th["min_margin"], floors) if len(test_y) else {}
    test_sources = np.array([r["source"] for r in split("test")])
    non_plant = test_sources == "caltech101"
    non_plant_fa = (float(answered_mask(test_p[non_plant], labels, th["min_prob"], th["min_margin"], floors)[1].mean())
                    if non_plant.any() else None)
    test_acc = float((test_p.argmax(1) == test_y).mean()) if len(test_y) else 0.0
    log(f"thresholds {th} -> test {test_m}, top-1 accuracy {test_acc:.4f}")

    onnx_path = out_dir / "leaf_classifier.onnx"
    export_onnx(model, onnx_path)
    if isinstance(model, Ensemble):
        compress_weights(onnx_path)  # int8 weights + scales in the file; computed in fp32 after loading
    sample_rows = (split("test") or split("val") or split("train"))[:8]
    samples = torch.stack([to_tensor(load_rgb(r["path"])) for r in sample_rows])
    parity = onnx_parity(model, onnx_path, samples)
    log(f"onnx parity max |diff| {parity:.2e}")
    if parity > 1e-3:
        raise RuntimeError(f"ONNX output differs from PyTorch by {parity}")

    test_set, train_set = describe_sets(rows)
    metadata = {
        "version": version, "stub": False, "arch": arch or config.ARCH, "input_size": config.INPUT_SIZE,
        "resize": "direct_bilinear", "mean": config.MEAN, "std": config.STD, "labels": list(labels),
        "temperature": round(temperature, 4),
        "thresholds": {"min_prob": th["min_prob"], "min_margin": th["min_margin"]},
        "per_class_min_prob": floors,
        "eval": {
            "test_set": test_set,
            "n": int(len(test_y)), "accuracy": round(test_acc, 4),
            "coverage_at_threshold": round(test_m.get("coverage", 0.0), 4),
            "selective_accuracy": round(test_m.get("selective_accuracy", 0.0), 4),
            "other_false_accept": round(test_m.get("other_false_accept", 0.0), 4),
            "thresholds_met_target": bool(th.get("met_target")), "target_selective_accuracy": target_accuracy,
            "non_plant_false_accept": None if non_plant_fa is None else round(non_plant_fa, 4),
            "non_plant_test_n": int(non_plant.sum()),
            "by_crop": by_crop(test_p, test_y, labels, th["min_prob"], th["min_margin"], floors) if len(test_y) else {},
            "by_source": by_source(test_p, test_y, [r["source"] for r in split("test")], labels, th["min_prob"],
                                   th["min_margin"], floors) if len(test_y) else {},
        },
        "training": {"train_set": train_set, **training,
                     "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds")},
        "lineage": lineage or {},
    }
    (out_dir / "leaf_classifier.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    write_reports(out_dir, labels, test_p, test_y, calib_p, calib_y, history, manifest_stats, metadata, th)
    log(f"wrote {onnx_path} ({onnx_path.stat().st_size // 1024} KB) + leaf_classifier.json + reports/")
    return metadata


class Ensemble(torch.nn.Module):
    """Average of the members' probabilities, each at its own fitted temperature. Returns log-probabilities, which
    the app uses as logits (softmax(log p) = p), so the classifier contract (§1) does not change."""

    def __init__(self, members, temperatures):
        super().__init__()
        self.members = torch.nn.ModuleList(members)
        self.register_buffer("inv_t", torch.tensor([1.0 / t for t in temperatures], dtype=torch.float32))

    def forward(self, x):
        probs = torch.stack([F.softmax(m(x) * self.inv_t[i], dim=1) for i, m in enumerate(self.members)]).mean(0)
        return torch.log(probs.clamp_min(1e-8))


@torch.no_grad()
def round_weights_int8(model) -> None:
    """Round every conv/linear weight to int8 per output channel, in place, so that the PyTorch model evaluated
    here is exactly what compress_weights() stores in the ONNX file."""
    for module in model.modules():
        if isinstance(module, (torch.nn.Conv2d, torch.nn.Linear)):
            w = module.weight
            scale = w.abs().flatten(1).amax(1).clamp_min(1e-12) / 127
            shape = (-1,) + (1,) * (w.dim() - 1)
            w.copy_((w / scale.view(shape)).round().clamp(-127, 127) * scale.view(shape))


def compress_weights(path: Path) -> None:
    """Store each Conv/Gemm weight as int8 + per-channel scale behind a DequantizeLinear node. ONNX Runtime folds
    it back to fp32 when the session loads, so inference stays fp32; only the file gets ~4x smaller."""
    import onnx
    from onnx import helper, numpy_helper
    m = onnx.load(str(path))
    inits = {i.name: i for i in m.graph.initializer}
    weights = {n.input[1] for n in m.graph.node if n.op_type in ("Conv", "Gemm") and len(n.input) > 1 and n.input[1] in inits}
    nodes = []
    for name in sorted(weights):
        w = numpy_helper.to_array(inits[name]).astype(np.float32)
        if w.ndim < 2:
            continue
        scale = np.maximum(np.abs(w.reshape(w.shape[0], -1)).max(1), 1e-12) / 127
        q = np.clip(np.round(w / scale.reshape((-1,) + (1,) * (w.ndim - 1))), -127, 127).astype(np.int8)
        m.graph.initializer.remove(inits[name])
        m.graph.initializer.extend([numpy_helper.from_array(q, name + "_q"),
                                    numpy_helper.from_array(scale.astype(np.float32), name + "_scale"),
                                    numpy_helper.from_array(np.zeros(w.shape[0], np.int8), name + "_zp")])
        nodes.append(helper.make_node("DequantizeLinear", [name + "_q", name + "_scale", name + "_zp"], [name], axis=0))
    existing = list(m.graph.node)  # dequantize first; the convs then read the fp32 weights under their old names
    del m.graph.node[:]
    m.graph.node.extend(nodes + existing)
    onnx.checker.check_model(m)
    onnx.save(m, str(path))


def ensemble(member_dirs: list, rows, labels, out_dir: Path, manifest_stats: dict, device=None, workers=4,
             target_accuracy=0.90, version=None, log=print, lineage: dict = None) -> dict:
    """Average trained models (same recipe, other seeds), then calibrate, evaluate and export like a single model."""
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = drop_unreadable(rows, log)
    members, temperatures, versions = [], [], []
    for d in member_dirs:
        meta = json.loads((d / "leaf_classifier.json").read_text(encoding="utf-8"))
        assert meta["labels"] == list(labels) and meta["input_size"] == config.INPUT_SIZE, d
        m = create_model(len(labels), pretrained=False)
        m.load_state_dict(torch.load(d / "model_best.pt", map_location="cpu"))
        members.append(m.eval())
        temperatures.append(meta["temperature"])
        versions.append(meta["version"])
    model = Ensemble(members, temperatures).eval()
    round_weights_int8(model)
    log(f"{version}: ensemble of {versions}, temperatures {temperatures}")
    training = {"members": versions, "member_temperatures": temperatures,
                "weights": "int8 per output channel (DequantizeLinear), computed in fp32"}
    return finish(model, rows, labels, out_dir, manifest_stats, [], version, device, workers, target_accuracy, log,
                  training, lineage, arch=f"ensemble of {len(members)} x {config.ARCH}")


def write_reports(out_dir, labels, test_p, test_y, calib_p, calib_y, history, manifest_stats, metadata, th):
    reports = out_dir / "reports"
    reports.mkdir(exist_ok=True)
    n = len(labels)
    confusion = np.zeros((n, n), dtype=int)
    for truth, pred in zip(test_y, test_p.argmax(1) if len(test_y) else []):
        confusion[truth, pred] += 1
    with open(reports / "confusion_test.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["true \\ predicted"] + list(labels))
        for i, label in enumerate(labels):
            w.writerow([label] + confusion[i].tolist())
    with open(reports / "coverage_curve_test.csv", "w", newline="") as f:  # accuracy vs coverage, margin fixed
        w = csv.writer(f)
        w.writerow(["min_prob", "min_margin", "coverage", "selective_accuracy", "other_false_accept"])
        for min_prob in np.arange(0.3, 1.0, 0.05):
            m = selective_metrics(test_p, test_y, labels, float(min_prob), th["min_margin"]) if len(test_y) else {}
            w.writerow([round(float(min_prob), 2), th["min_margin"], m.get("coverage"), m.get("selective_accuracy"), m.get("other_false_accept")])
    per_class = {}
    for i, label in enumerate(labels):
        support = int(confusion[i].sum())
        tp = int(confusion[i, i])
        predicted = int(confusion[:, i].sum())
        per_class[label] = {"support": support, "recall": tp / support if support else None,
                            "precision": tp / predicted if predicted else None}
    (reports / "metrics.json").write_text(json.dumps({
        "eval": metadata["eval"], "thresholds_calib": th, "per_class_test": per_class,
        "history": history, "manifest": manifest_stats}, indent=2) + "\n", encoding="utf-8")
