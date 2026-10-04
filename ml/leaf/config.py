"""Labels and dataset registry for the leaf classifier. Every source lists its licence (README.md, Data)."""

# Contracts section 1. P0 ships coffee + other; P1 adds the maize and bean labels that have a training source
# (PlantDoc corn blight / grey leaf spot, iBean Uganda beans). maize_healthy and maize_fall_armyworm need a source
# first (e.g. Makerere fall armyworm images), so they are not in P1 yet: a label without images cannot be learned.
P0_LABELS = ["coffee_healthy", "coffee_rust", "coffee_miner", "coffee_cercospora", "coffee_phoma", "other"]
P1_LABELS = P0_LABELS[:-1] + [
    "maize_leaf_blight", "maize_leaf_spot", "bean_healthy", "bean_angular_leaf_spot", "bean_rust", "other"]
# P2 adds the maize classes from CCMT (Ghana field photos): healthy maize, fall armyworm and streak virus.
P2_LABELS = P1_LABELS[:-1] + ["maize_healthy", "maize_fall_armyworm", "maize_streak_virus", "other"]
LABEL_SETS = {"p0": P0_LABELS, "p1": P1_LABELS, "p2": P2_LABELS}

MEAN = [0.485, 0.456, 0.406]   # ImageNet; matches timm mobilenetv4 pretrained config
STD = [0.229, 0.224, 0.225]
INPUT_SIZE = 224
ARCH = "mobilenetv4_conv_small.e2400_r224_in1k"

MENDELEY = "https://data.mendeley.com/public-files/datasets/{dataset}/files/{file}/file_downloaded"

SOURCES = {
    # Train/val for coffee. Brazil (Espirito Santo), white background. The published zip has no central
    # directory (truncated upload, ~1,400 of 1,747 leaf images survive), so it is read with a streaming extractor.
    "bracol": {
        "files": {"bracol.zip": MENDELEY.format(dataset="yy2k5y8mxg", file="c16b08ee-3ca6-4bf0-8f4e-4285a53a4a24")},
        "licence": "CC BY 4.0", "country": "Brazil", "role": "train",
        "page": "https://data.mendeley.com/datasets/yy2k5y8mxg/1",
    },
    # Train/val/test for coffee. Kenya (Kirinyaga). 128 px close-ups of single lesions, not whole leaves, so a model
    # trained on BRACOL alone calls every one `other`: it is trained on (split by duplicate group) and tested
    # same-source. Contains rotated/flipped copies: de-duplicated.
    "jmuben": {
        "files": {
            "cercospora.zip": MENDELEY.format(dataset="t2r6rszp5c", file="8657d2a2-c9a1-4733-9dbc-00c83aa3575a"),
            "rust.zip": MENDELEY.format(dataset="t2r6rszp5c", file="8c7c2915-f979-43f6-b3fd-b3bc7407da87"),
            "phoma.zip": MENDELEY.format(dataset="t2r6rszp5c", file="82625dd3-e908-4224-93b5-06a3b74f0c8a"),
        },
        "licence": "CC BY 4.0", "country": "Kenya", "role": "train",
        "page": "https://data.mendeley.com/datasets/t2r6rszp5c/1",
    },
    "jmuben2": {
        "files": {
            "healthy.zip": MENDELEY.format(dataset="tgv3zb82nd", file="d126777d-c495-4b7a-846a-c0228540ea10"),
            "miner.zip": MENDELEY.format(dataset="tgv3zb82nd", file="f6d37632-6349-4be9-9af0-c3177dbfaa8a"),
        },
        "licence": "CC BY 4.0", "country": "Kenya", "role": "train",
        "page": "https://data.mendeley.com/datasets/tgv3zb82nd/1",
    },
    # Cross-country calib + test for coffee. Ecuador robusta, smartphone photos of leaves on the plant (the closest
    # public match to a farmer's photo; Uganda grows mostly robusta). Split by plant; never trained on (split "xc").
    # One file per image on Mendeley: leaf/rocole_files.csv lists the S3 URLs (made by leaf/resolve_rocole.py).
    "rocole": {
        "file_list": "rocole_files.csv",
        "licence": "CC BY 4.0", "country": "Ecuador", "role": "test",
        "page": "https://data.mendeley.com/datasets/c5yvn32dzg/2",
    },
    # Maize classes (P2). Ghana field photos from local farms (CCMT raw data), one file per image. Mendeley blocks
    # cloud IPs, so leaf/ccmt_files.csv lists each image's public S3 URL (made by leaf/resolve_ccmt.py).
    "ccmt": {
        "file_list": "ccmt_files.csv",
        "licence": "CC BY 4.0", "country": "Ghana", "role": "maize",
        "page": "https://data.mendeley.com/datasets/bwh3zbpkpv/1",
    },
    # `other` (P0) and maize classes (P1). Web-scraped field photos, 13 species, no coffee.
    "plantdoc": {
        "files": {"plantdoc.zip": "https://github.com/pratikkayal/PlantDoc-Dataset/archive/refs/heads/master.zip"},
        "licence": "CC BY 4.0", "country": "web (mixed)", "role": "other",
        "page": "https://github.com/pratikkayal/PlantDoc-Dataset",
    },
    # `other` (P0) and bean classes (P1). Uganda field photos (Makerere AI Lab iBean), Hugging Face mirror.
    "ibean": {
        "files": {f"{split}.zip": f"https://huggingface.co/datasets/AI-Lab-Makerere/beans/resolve/main/data/{split}.zip"
                  for split in ("train", "validation", "test")},
        "licence": "MIT", "country": "Uganda", "role": "other",
        "page": "https://huggingface.co/datasets/AI-Lab-Makerere/beans",
    },
}

# BRACOL leaf/dataset.csv `predominant_stress`, verified against its per-stress flag columns:
# 0 healthy, 1 miner, 2 rust, 3 phoma, 4 cercospora, 5 = mixed stresses (excluded: no single label).
BRACOL_STRESS = {"0": "coffee_healthy", "1": "coffee_miner", "2": "coffee_rust", "3": "coffee_phoma", "4": "coffee_cercospora"}

# JMuBEN folder names (Google Drive exports) -> label, matched as lowercase substrings.
JMUBEN_FOLDERS = [("cerc", "coffee_cercospora"), ("cersc", "coffee_cercospora"), ("rust", "coffee_rust"),
                  ("phoma", "coffee_phoma"), ("health", "coffee_healthy"), ("miner", "coffee_miner")]

# P1 mappings (used only when labels = P1_LABELS); anything unmapped from these sources becomes `other`.
# `other` for every label set: objects, animals and scenes (CaltechDATA, CC BY 4.0), so `other` is not only "a plant
# we don't support". Without it, 6 of 20 random non-plant photos got a CONFIDENT disease (A, 2026-10-04).
SOURCES["caltech101"] = {
    "files": {"caltech-101.zip": "https://data.caltech.edu/records/mzrjq-6wc02/files/caltech-101.zip?download=1"},
    "licence": "CC BY 4.0", "country": "web (objects, animals, scenes)", "role": "other",
    "page": "https://data.caltech.edu/records/mzrjq-6wc02",
}
CALTECH_PER_CATEGORY = 40  # 102 categories (incl. BACKGROUND_Google); capped so faces/airplanes don't dominate

PLANTDOC_P1 = {"corn leaf blight": "maize_leaf_blight", "corn gray leaf spot": "maize_leaf_spot"}
IBEAN_P1 = {"angular_leaf_spot": "bean_angular_leaf_spot", "bean_rust": "bean_rust", "healthy": "bean_healthy"}

# Mendeley blocks cloud IPs (403 from Modal), but its files live on public, unsigned S3 objects.
# Resolved from a residential IP on 2026-10-04; the Mendeley URLs above stay the citation.
S3_MIRRORS = {
    "https://data.mendeley.com/public-files/datasets/yy2k5y8mxg/files/c16b08ee-3ca6-4bf0-8f4e-4285a53a4a24/file_downloaded":
        "https://prod-dcd-datasets-public-files-eu-west-1.s3.eu-west-1.amazonaws.com/74af38d1-f2ac-492c-83b3-c6bfe92368ac",
    "https://data.mendeley.com/public-files/datasets/t2r6rszp5c/files/8657d2a2-c9a1-4733-9dbc-00c83aa3575a/file_downloaded":
        "https://prod-dcd-datasets-public-files-eu-west-1.s3.eu-west-1.amazonaws.com/237c4edc-4565-4884-81fe-6418c833c654",
    "https://data.mendeley.com/public-files/datasets/t2r6rszp5c/files/8c7c2915-f979-43f6-b3fd-b3bc7407da87/file_downloaded":
        "https://prod-dcd-datasets-public-files-eu-west-1.s3.eu-west-1.amazonaws.com/690545a1-0f10-42f4-92d1-fd14ef5b1d35",
    "https://data.mendeley.com/public-files/datasets/t2r6rszp5c/files/82625dd3-e908-4224-93b5-06a3b74f0c8a/file_downloaded":
        "https://prod-dcd-datasets-public-files-eu-west-1.s3.eu-west-1.amazonaws.com/10ae2322-69f4-4b9e-9086-0c82b538f747",
    "https://data.mendeley.com/public-files/datasets/tgv3zb82nd/files/d126777d-c495-4b7a-846a-c0228540ea10/file_downloaded":
        "https://prod-dcd-datasets-public-files-eu-west-1.s3.eu-west-1.amazonaws.com/e54a6f09-b449-4eb8-8189-d7e345c36576",
    "https://data.mendeley.com/public-files/datasets/tgv3zb82nd/files/f6d37632-6349-4be9-9af0-c3177dbfaa8a/file_downloaded":
        "https://prod-dcd-datasets-public-files-eu-west-1.s3.eu-west-1.amazonaws.com/ee37230e-4f7c-458d-97eb-cd8d684e5607",
}
