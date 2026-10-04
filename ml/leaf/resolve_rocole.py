"""Resolve RoCoLe (Ecuador, CC BY 4.0) robusta leaf photos to their public S3 URLs -> ml/leaf/rocole_files.csv.

RoCoLe is 1,560 smartphone photos of robusta coffee leaves still on the plant (390 plants x 4 photos), the
closest public match to a farmer's own photo. It is the cross-country coffee calib/test set and is never trained
on. Labels come from the dataset's own RoCoLE-csv.csv: healthy -> coffee_healthy, rust_level_1..4 -> coffee_rust;
red spider mite has no label of ours and is skipped. Like CCMT, Mendeley blocks cloud IPs, so run this once from
a normal connection; Modal then downloads the S3 URLs directly. The Mendeley URL stays in the CSV as the citation.

Usage (from ml/): python -m leaf.resolve_rocole
"""
import csv
import io
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

BASE = "https://data.mendeley.com/public-api/datasets/c5yvn32dzg"
LABELS_FILE = "RoCoLE-csv.csv"
CLASSES = {"healthy": "coffee_healthy", "rust_level_1": "coffee_rust", "rust_level_2": "coffee_rust",
           "rust_level_3": "coffee_rust", "rust_level_4": "coffee_rust"}
OUT = Path(__file__).resolve().parent / "rocole_files.csv"


def resolve(item):
    label, name, url = item
    for attempt in range(6):  # Mendeley sometimes answers 200 without the redirect under load: retry with backoff
        r = requests.head(url, allow_redirects=False, timeout=60)
        location = r.headers.get("Location", "")
        if r.status_code in (301, 302, 303, 307) and "amazonaws.com" in location:
            break
        time.sleep(1.5 * (attempt + 1))
    else:
        raise RuntimeError(f"{name}: {r.status_code} {location[:80]}")
    plant = re.match(r"(C\d+P\d+)", name).group(1)  # 4 photos per plant: they must stay in one split
    return {"folder": label, "label": label, "filename": name, "group": f"rocole:{plant}",
            "mendeley_url": url, "s3_url": location}


if __name__ == "__main__":
    files = {f["filename"]: f["content_details"]["download_url"]
             for f in requests.get(BASE, timeout=120).json()["files"]}
    table = requests.get(files[LABELS_FILE], timeout=120)
    table.raise_for_status()
    items = []
    for r in csv.DictReader(io.StringIO(table.content.decode("utf-8-sig"))):
        label = CLASSES.get(json.loads(r["Label"])["classification"])
        if label and r["External ID"] in files:
            items.append((label, r["External ID"], files[r["External ID"]]))
    with ThreadPoolExecutor(max_workers=12) as pool:
        rows = sorted(pool.map(resolve, items), key=lambda r: (r["folder"], r["filename"]))
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    from collections import Counter
    print(f"wrote {OUT.name}: {len(rows)} files", dict(Counter(r["label"] for r in rows)))
