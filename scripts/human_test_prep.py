#!/usr/bin/env python3
"""Prepare the two emulators for a human test session.

  python3 scripts/human_test_prep.py            # test photos into both galleries, SMS lab numbers printed
  python3 scripts/human_test_prep.py --fresh    # also wipe the app on both phones (first-run screens), then
                                                # put the side-loaded language model back on the helper phone
                                                # (= make human-test; run it before each tester)
  python3 scripts/human_test_prep.py --fresh --no-model   # same, without the model: the tester downloads it

Test photos: 4 RoCoLe leaves from plants the classifier never trained on (2 rust, 2 healthy, recomputed with the
same seed as ml/leaf/data.py), 1 blurred copy (should ask for a retake) and 1 picture that is not a plant.
Needs: adb, internet once (photos are cached in /tmp/pandastic-human-test), Pillow + requests.
"""
import csv
import io
import random
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import requests
from PIL import Image, ImageDraw, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path.home() / "Android/Sdk/platform-tools/adb") if (Path.home() / "Android/Sdk/platform-tools/adb").exists() else "adb"
HUB, BASIC = "emulator-5554", "emulator-5556"
PACKAGE = "org.pandastic.relay"
CACHE = Path("/tmp/pandastic-human-test")
GALLERY = "/sdcard/Pictures/PandasticTest"
MODELS = ["Qwen3.5-0.8B-pandastic-Q4_K_M.gguf", "Qwen3.5-0.8B-Q4_K_M.gguf"]


def adb(serial, *args, check=True):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, check=check).stdout.strip()


def online(serial):
    return subprocess.run([ADB, "-s", serial, "get-state"], capture_output=True, text=True).stdout.strip() == "device"


def held_out_plants():
    """RoCoLe plants in the 'mix' test split (same rule and seed as leaf/data.py: per plant, seed 13)."""
    rows = list(csv.DictReader(open(ROOT / "ml/leaf/rocole_files.csv", encoding="utf-8")))
    groups = defaultdict(list)
    for r in rows:
        groups[r["group"]].append(r)
    keys = sorted(groups)
    random.Random(13).shuffle(keys)
    shares = [("train", 0.5), ("val", 0.1), ("calib", 0.15), ("test", 0.25)]
    start, test = 0, []
    for i, (split, share) in enumerate(shares):
        end = len(keys) if i == len(shares) - 1 else start + round(share * len(keys))
        if split == "test":
            test = keys[start:end]
        start = end
    return [r for key in test for r in groups[key]]


def photos():
    CACHE.mkdir(exist_ok=True)
    rows = held_out_plants()
    picks, plants = [], set()
    for label in ("coffee_rust", "coffee_rust", "coffee_healthy", "coffee_healthy"):  # four different plants
        r = next(r for r in rows if r["label"] == label and r["group"] not in plants)
        picks.append(r)
        plants.add(r["group"])
    out = []
    for n, r in enumerate(picks, 1):
        target = CACHE / f"leaf{n}.jpg"  # neutral names: the tester must not see the answer
        if not target.exists():
            data = requests.get(r["s3_url"], timeout=120).content
            # Keep the photo upright: RoCoLe files carry EXIF orientation, which saving would drop (B, 05:01).
            ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB").save(target, quality=92)
        out.append((target, r["label"], r["filename"]))
    blurred = CACHE / "leaf5.jpg"
    Image.open(out[2][0]).filter(ImageFilter.GaussianBlur(25)).save(blurred, quality=92)
    out.append((blurred, "retake (blurred)", out[2][2]))
    other = CACHE / "picture6.jpg"
    im = Image.new("RGB", (1280, 960), (196, 160, 112))  # a wooden table with a mug: not a plant
    d = ImageDraw.Draw(im)
    for x in range(0, 1280, 40):
        d.line([(x, 0), (x + 200, 960)], fill=(170, 130, 90), width=6)
    d.ellipse((480, 300, 800, 620), fill=(240, 240, 235)); d.ellipse((560, 380, 720, 540), fill=(90, 50, 30))
    im.save(other, quality=92)
    out.append((other, "not a plant", "-"))
    return out


def push_photos(serial, files):
    adb(serial, "shell", "mkdir", "-p", GALLERY)
    for path, _, _ in files:
        adb(serial, "push", str(path), f"{GALLERY}/{path.name}")
    # Make them show up in the gallery picker.
    adb(serial, "shell", "content", "call", "--method", "scan_volume", "--uri", "content://media", "--arg", "external_primary", check=False)
    for path, _, _ in files:
        adb(serial, "shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", f"file://{GALLERY}/{path.name}", check=False)


def fresh(serial):
    adb(serial, "shell", "pm", "clear", PACKAGE)
    # Old SMS threads would confuse the next tester. Wiping them needs root, which only non-Play-Store emulator
    # images allow (the Basic phone's); the helper phone's Messages keeps its history.
    if "cannot run as root" not in adb(serial, "root", check=False):
        subprocess.run([ADB, "-s", serial, "wait-for-device"], check=False)
        adb(serial, "shell", "content", "delete", "--uri", "content://sms", check=False)
        print(f"{serial}: SMS history cleared")
    if serial != HUB or "--no-model" in sys.argv:
        return
    staged = adb(serial, "shell", "ls", "/data/local/tmp", check=False)
    restored = [m for m in MODELS if m in staged]
    if restored:
        adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/models")
        for m in restored:
            adb(serial, "shell", "run-as", PACKAGE, "cp", f"/data/local/tmp/{m}", "files/models/")
    print(f"{serial}: app data wiped (first-run screens); language model restored: {', '.join(restored) or 'none staged'}")


def main():
    devices = [s for s in (HUB, BASIC) if online(s)]
    if HUB not in devices:
        sys.exit(f"{HUB} is not running: make run")
    files = photos()
    for serial in devices:
        if "--fresh" in sys.argv:
            fresh(serial)
        push_photos(serial, files)
    # What a real phone already has: its own number and the other person in its Contacts app (not in Pandastic).
    subprocess.run(["node", str(ROOT / "scripts/sms-lab.mjs"), "seed"], check=False)
    for serial in devices:
        # Open the app so the tester starts on its first screen.
        adb(serial, "shell", "am", "start", "-W", "-S", "-n", f"{PACKAGE}/.FrontendActivity", check=False)
    print("\nPhotos in each gallery (Pictures/PandasticTest). Facilitator key, do not show testers:")
    for path, label, source in files:
        print(f"  {path.name:13s} {label:18s} {source}")
    print("""
Phones (the SMS carrier from ./run.sh must keep running):
  helper phone = Amani's (the daughter's) phone  emulator-5554  +256 772 000 001  Contacts: Mama Noor
  Noor's phone (use Messages, the normal SMS app) emulator-5556  +256 772 000 002  Contacts: Amani (binti)
  a kabambe in this terminal: make sms-phone            +256 772 000 003
Ready for the next tester. Reset between testers: make human-test""")


if __name__ == "__main__":
    main()
