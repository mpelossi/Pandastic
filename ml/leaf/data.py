"""Download, extract and index the leaf datasets into one manifest (path, label, source, split, group).

Splits (coffee_split "mix", the default; RoCoLe = Ecuador smartphone photos of leaves on the plant):
  train / val  BRACOL (Brazil, whole leaves on white paper) 85/15 + JMuBEN (Kenya, lesion close-ups,
               de-duplicated) 70/10 by duplicate group + RoCoLe 50/10 by plant + `other` 70/10.
               val drives early stopping and temperature scaling.
  calib / test RoCoLe 15/25 by plant (other plants than training: same source) + JMuBEN test 20% + `other`
               10/10. Thresholds are chosen on calib; numbers are reported on test only.
  coffee_split "xc" never trains on RoCoLe (calib 40 / test 60): a cross-country coffee test.
  eval.by_source says which slices were trained on and which were not.
JMuBEN was the calib/test set until 2026-10-04: its 128 px lesion crops look like no whole-leaf photo, and a
model trained on BRACOL alone called every one of them `other`.
"""
import csv
import hashlib
import os
import random
import shutil
import subprocess
import tarfile
import zipfile
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from . import config

IMAGE_EXT = (".jpg", ".jpeg", ".png", ".bmp", ".webp")


# ---------------------------------------------------------------- download + extract

def _download_file_list(name: str, work_dir: Path, log=print, limit: int = None) -> None:
    """Sources published as one file per image (CCMT): fetch every listed S3 URL straight into the work dir."""
    import requests
    from concurrent.futures import ThreadPoolExecutor
    listing = Path(__file__).resolve().parent / config.SOURCES[name]["file_list"]
    with open(listing, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))[:limit]
    dest = work_dir / name
    if (dest / ".done").exists():
        return
    log(f"download {name}: {len(rows)} files")

    def fetch_one(row):
        target = dest / row["folder"] / row["filename"]
        if target.exists() and target.stat().st_size > 0:
            return 0
        target.parent.mkdir(parents=True, exist_ok=True)
        for attempt in range(4):
            try:
                r = requests.get(row["s3_url"], timeout=120)
                r.raise_for_status()
                target.write_bytes(r.content)
                return 1
            except requests.RequestException:
                if attempt == 3:
                    raise
    with ThreadPoolExecutor(max_workers=16) as pool:
        fetched = sum(pool.map(fetch_one, rows))
    (dest / ".done").write_text("ok")
    log(f"  {name}: {fetched} new files")


def download(sources, raw_dir: Path, log=print, work_dir: Path = None) -> None:
    import requests
    for name in sources:
        if "file_list" in config.SOURCES[name]:
            _download_file_list(name, work_dir or raw_dir.parent / "work", log)
            continue
        for filename, url in config.SOURCES[name]["files"].items():
            target = raw_dir / name / filename
            if target.exists() and target.stat().st_size > 0:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            log(f"download {name}/{filename}")
            tmp = target.with_suffix(target.suffix + ".part")
            r = requests.get(url, stream=True, timeout=600, headers={"User-Agent": "pandastic-research"})
            if r.status_code == 403 and url in config.S3_MIRRORS:
                r.close()
                log(f"  403 from {url.split('/')[2]}; using its public S3 mirror")
                r = requests.get(config.S3_MIRRORS[url], stream=True, timeout=600)
            with r:
                r.raise_for_status()
                with open(tmp, "wb") as f:
                    for chunk in r.iter_content(1 << 20):
                        f.write(chunk)
            tmp.replace(target)


def extract(sources, raw_dir: Path, work_dir: Path, log=print) -> None:
    """zipfile first; archives without a central directory (BRACOL) fall back to bsdtar's streaming reader."""
    for name in sources:
        for filename in config.SOURCES[name].get("files", {}):  # file-list sources need no extraction
            archive = raw_dir / name / filename
            dest = work_dir / name / Path(filename).stem
            if (dest / ".done").exists():
                continue
            if dest.exists():
                shutil.rmtree(dest)
            dest.mkdir(parents=True)
            log(f"extract {name}/{filename}")
            try:
                with zipfile.ZipFile(archive) as z:
                    z.extractall(dest)
            except zipfile.BadZipFile:
                if not shutil.which("bsdtar"):
                    raise RuntimeError(f"{archive} has no zip directory; install libarchive-tools (bsdtar)")
                # bsdtar exits non-zero on the truncated last entry; keep everything it could read.
                subprocess.run(["bsdtar", "-xf", str(archive), "-C", str(dest)], check=False)
            for inner in sorted(dest.rglob("*.tar.gz")):  # Caltech-101 ships its images as a tar.gz inside the zip
                if inner.name.startswith("._") or "__MACOSX" in inner.parts:  # macOS resource forks, not archives
                    continue
                with tarfile.open(inner) as t:
                    t.extractall(inner.parent)
            (dest / ".done").write_text("ok")


# ---------------------------------------------------------------- de-duplication

def _canonical_phash(path: str) -> str:
    """Perceptual hash that is the same for all 8 rotations/flips of an image (JMuBEN contains such copies)."""
    import imagehash
    from PIL import Image, ImageOps
    try:
        with Image.open(path) as im:
            im = ImageOps.exif_transpose(im).convert("L").resize((64, 64))
    except Exception:
        return "unreadable:" + hashlib.md5(path.encode()).hexdigest()
    variants = []
    for flipped in (im, ImageOps.mirror(im)):
        for angle in (0, 90, 180, 270):
            variants.append(str(imagehash.phash(flipped.rotate(angle))))
    return min(variants)


def phash_groups(paths, workers: int = os.cpu_count() or 2) -> dict:
    """path -> group id; images whose canonical pHash is identical share a group."""
    with ProcessPoolExecutor(max_workers=workers) as pool:
        hashes = list(pool.map(_canonical_phash, paths, chunksize=64))
    return dict(zip(paths, hashes))


# ---------------------------------------------------------------- manifest

def _images(root: Path):
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.lower().endswith(IMAGE_EXT) and not f.startswith("._"):
                yield Path(dirpath) / f


def _folder_label(path: Path, mapping):
    for part in reversed([p.lower() for p in path.parts[:-1]]):
        for key, label in mapping:
            if key in part:
                return label
    return None


def _bracol_rows(work_dir: Path, labels):
    csvs = list((work_dir / "bracol").rglob("leaf/dataset.csv"))
    if not csvs:
        return []
    table = csvs[0]
    images = {p.stem: p for p in (table.parent / "images").glob("*.jpg")}
    rows = []
    with open(table, newline="") as f:
        for r in csv.DictReader(f):
            label = config.BRACOL_STRESS.get(r["predominant_stress"])
            if label in labels and r["id"] in images and images[r["id"]].stat().st_size > 0:
                rows.append({"path": str(images[r["id"]]), "label": label, "source": "bracol", "group": f"bracol:{r['id']}"})
    return rows


def _jmuben_rows(work_dir: Path, labels, workers):
    paths = [str(p) for name in ("jmuben", "jmuben2") for p in _images(work_dir / name)]
    groups = phash_groups(paths, workers) if paths else {}
    rows = []
    for p in paths:
        label = _folder_label(Path(p), config.JMUBEN_FOLDERS)
        if label in labels:
            rows.append({"path": p, "label": label, "source": "jmuben", "group": "jmuben:" + groups[p]})
    return rows


def _ccmt_rows(work_dir: Path, labels, workers):
    """CCMT raw maize photos: folder name -> label (see leaf/resolve_ccmt.py); duplicate groups by pHash."""
    from .resolve_ccmt import FOLDERS
    paths = [str(p) for p in _images(work_dir / "ccmt") if FOLDERS.get(p.parent.name) in labels]
    groups = phash_groups(paths, workers) if paths else {}
    return [{"path": p, "label": FOLDERS[Path(p).parent.name], "source": "ccmt", "group": "ccmt:" + groups[p]} for p in paths]


def _rocole_rows(work_dir: Path, labels):
    """RoCoLe photos listed in leaf/rocole_files.csv (label + plant group per file), as downloaded into the work dir."""
    listing = Path(__file__).resolve().parent / config.SOURCES["rocole"]["file_list"]
    rows = []
    with open(listing, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            path = work_dir / "rocole" / r["folder"] / r["filename"]
            if r["label"] in labels and path.exists():
                rows.append({"path": str(path), "label": r["label"], "source": "rocole", "group": r["group"]})
    return rows


def _other_rows(work_dir: Path, labels):
    rows = []
    for name, p1_map in (("plantdoc", config.PLANTDOC_P1), ("ibean", config.IBEAN_P1)):
        for p in _images(work_dir / name):
            label = "other"
            if "other" not in labels:
                continue
            folder = p.parent.name.lower()
            for key, mapped in p1_map.items():
                if key == folder and mapped in labels:
                    label = mapped
            rows.append({"path": str(p), "label": label, "source": name, "group": f"{name}:{p.stem}"})
    return rows


def _caltech_rows(work_dir: Path, labels):
    """Caltech-101 objects/animals/scenes as `other`, grouped by category so whole categories are held out."""
    if "other" not in labels:
        return []
    rows = []
    for category in sorted(p for p in (work_dir / "caltech101").rglob("101_ObjectCategories/*") if p.is_dir()):
        for p in sorted(_images(category))[: config.CALTECH_PER_CATEGORY]:
            rows.append({"path": str(p), "label": "other", "source": "caltech101", "group": f"caltech101:{category.name}"})
    return rows


def _split_by_group(rows, fractions, seed, per_label: bool = True):
    """Assign splits per (label, group) so duplicates never cross splits. fractions: [(split, share), ...].
    per_label=False splits whole groups (e.g. a plant's healthy and rusty leaves stay together)."""
    by_label = defaultdict(lambda: defaultdict(list))
    for r in rows:
        by_label[r["label"] if per_label else ""][r["group"]].append(r)
    rng = random.Random(seed)
    for label, groups in by_label.items():
        keys = sorted(groups)
        rng.shuffle(keys)
        start = 0
        for i, (split, share) in enumerate(fractions):
            end = len(keys) if i == len(fractions) - 1 else start + round(share * len(keys))
            for key in keys[start:end]:
                for r in groups[key]:
                    r["split"] = split
            start = end


# How RoCoLe (the coffee calib/test source) is split, by plant.
COFFEE_SPLITS = {
    "xc": [("calib", 0.4), ("test", 0.6)],  # never trained on: a cross-country test
    "mix": [("train", 0.5), ("val", 0.1), ("calib", 0.15), ("test", 0.25)],  # held-out plants, same source
}


def build_manifest(work_dir: Path, labels, seed: int = 13, max_test_per_class: int = 1000,
                   workers: int = os.cpu_count() or 2, log=print, coffee_split: str = "mix"):
    labels = set(labels)
    bracol = _bracol_rows(work_dir, labels)
    _split_by_group(bracol, [("train", 0.85), ("val", 0.15)], seed)

    jmuben = _jmuben_rows(work_dir, labels, workers)
    # Keep one image per duplicate group: the test then counts distinct leaves, not augmented copies.
    first = {}
    for r in jmuben:
        first.setdefault(r["group"], r)
    jmuben_unique = list(first.values())
    log(f"jmuben: {len(jmuben)} images -> {len(jmuben_unique)} after rotation/flip-invariant pHash de-duplication")
    rng = random.Random(seed)
    capped = []
    for label in sorted({r["label"] for r in jmuben_unique}):
        items = [r for r in jmuben_unique if r["label"] == label]
        rng.shuffle(items)
        capped += items[: 2 * max_test_per_class]
    _split_by_group(capped, [("train", 0.7), ("val", 0.1), ("test", 0.2)], seed)

    rocole = _rocole_rows(work_dir, labels)
    log(f"rocole: {len(rocole)} photos from {len({r['group'] for r in rocole})} plants, split {coffee_split}")
    _split_by_group(rocole, COFFEE_SPLITS[coffee_split], seed, per_label=False)

    other = _other_rows(work_dir, labels)
    _split_by_group(other, [("train", 0.7), ("val", 0.1), ("calib", 0.1), ("test", 0.1)], seed)

    # Maize from CCMT (Ghana): same-source split (there is no second maize source for these classes).
    ccmt = _ccmt_rows(work_dir, labels, workers)
    _split_by_group(ccmt, [("train", 0.7), ("val", 0.1), ("calib", 0.1), ("test", 0.1)], seed)

    # Non-plant `other` split by category: the calib and test categories are objects the model never saw.
    caltech = _caltech_rows(work_dir, labels)
    _split_by_group(caltech, [("train", 0.6), ("val", 0.1), ("calib", 0.15), ("test", 0.15)], seed)

    rows = bracol + capped + rocole + other + ccmt + caltech
    stats = {
        "counts": {f"{s}/{l}": c for (s, l), c in sorted(Counter((r["split"], r["label"]) for r in rows).items())},
        "by_source": {f"{s}/{src}": c for (s, src), c in sorted(Counter((r["split"], r["source"]) for r in rows).items())},
        "jmuben_raw": len(jmuben), "jmuben_unique": len(jmuben_unique), "coffee_split": coffee_split,
        "labels_without_training_data": sorted(labels - {r["label"] for r in rows if r["split"] == "train"}),
    }
    return rows, stats


# ---------------------------------------------------------------- pre-resized cache

CACHE_SHORT_SIDE = 320  # a 60%-area random crop of a 320 px image is still >= 224 px


def _cache_one(job):
    """Decode, orient and shrink one image; the cached JPEG is what training and evaluation read."""
    src, dst, short_side = job
    if os.path.exists(dst):
        return True
    try:
        from PIL import Image, ImageFile, ImageOps
        ImageFile.LOAD_TRUNCATED_IMAGES = True  # a few source JPEGs end a few bytes early
        with Image.open(src) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
        w, h = im.size
        scale = short_side / min(w, h)
        if scale < 1:
            im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.BILINEAR)
        tmp = dst + ".tmp"
        im.save(tmp, "JPEG", quality=95)
        os.replace(tmp, dst)
        return True
    except Exception:
        return False


def build_cache(rows, cache_dir: Path, short_side: int = CACHE_SHORT_SIDE, workers: int = os.cpu_count() or 2,
                log=print):
    """Decode every image once, before any GPU starts.

    Full-size JPEG decoding per epoch left the L4 ~90% idle, and a non-image file crashed evaluation after
    12 epochs. Unreadable files are dropped here instead. Idempotent: cached files are reused across runs.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    jobs = [(r["path"], str(cache_dir / (hashlib.sha1(r["path"].encode()).hexdigest() + ".jpg")), short_side) for r in rows]
    with ProcessPoolExecutor(workers) as pool:
        ok = list(pool.map(_cache_one, jobs, chunksize=32))
    kept = [{**r, "original": r["path"], "path": dst} for r, (_, dst, _), good in zip(rows, jobs, ok) if good]
    dropped = [r["path"] for r, good in zip(rows, ok) if not good]
    if dropped:
        log(f"cache: dropped {len(dropped)} unreadable files, e.g. {dropped[:3]}")
    log(f"cache: {len(kept)} images at short side {short_side} px in {cache_dir}")
    return kept, dropped


def write_manifest(rows, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["path", "label", "source", "split", "group"] + (["original"] if rows and "original" in rows[0] else [])
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def read_manifest(path: Path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))
