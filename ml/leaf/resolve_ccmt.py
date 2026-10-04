"""Resolve CCMT (Ghana, CC BY 4.0) raw maize images to their public S3 URLs -> ml/leaf/ccmt_files.csv.

Mendeley serves each CCMT image as a separate file and blocks cloud IPs (403 from Modal), but every file
redirects to an unsigned public S3 object. Run this once from a normal connection; Modal then downloads the
S3 URLs directly. The Mendeley URL stays in the CSV as the citation.

Usage (from ml/): python -m leaf.resolve_ccmt
"""
import csv
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

BASE = "https://data.mendeley.com/public-api/datasets/bwh3zbpkpv"
RAW_MAIZE = "a19b7603-2a7e-4a14-8c9c-a25d201e3b03"  # Raw Data/CCMT Dataset/Maize
# Folders that match our labels. Grasshopper and leaf-beetle damage are maize leaves too, so they are skipped
# rather than taught as `other`.
FOLDERS = {"fall armyworm": "maize_fall_armyworm", "healthy": "maize_healthy", "leaf blight": "maize_leaf_blight",
           "leaf spot": "maize_leaf_spot", "streak virus": "maize_streak_virus"}
OUT = Path(__file__).resolve().parent / "ccmt_files.csv"


def resolve(item):
    folder, name, url = item
    for attempt in range(6):  # Mendeley sometimes answers 200 without the redirect under load: retry with backoff
        r = requests.head(url, allow_redirects=False, timeout=60)
        location = r.headers.get("Location", "")
        if r.status_code in (301, 302, 303, 307) and "amazonaws.com" in location:
            break
        time.sleep(1.5 * (attempt + 1))
    else:
        raise RuntimeError(f"{name}: {r.status_code} {location[:80]}")
    return {"folder": folder, "label": FOLDERS[folder], "filename": name, "mendeley_url": url, "s3_url": location}


if __name__ == "__main__":
    folders = [f for f in requests.get(f"{BASE}/folders/1", timeout=60).json() if f.get("parent_id") == RAW_MAIZE]
    items = []
    for f in folders:
        if f["name"] not in FOLDERS:
            continue
        for x in requests.get(f"{BASE}/files", params={"folder_id": f["id"], "version": 1}, timeout=60).json():
            items.append((f["name"], x["filename"], x["content_details"]["download_url"]))
    with ThreadPoolExecutor(max_workers=12) as pool:
        rows = sorted(pool.map(resolve, items), key=lambda r: (r["folder"], r["filename"]))
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    from collections import Counter
    print(f"wrote {OUT.name}: {len(rows)} files", dict(Counter(r["label"] for r in rows)))
