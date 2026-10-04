"""Build android/app/src/main/assets/models/knowledge.sqlite from the curated files in data/.

Inputs (all committed): data/sources.csv, data/advice.json, data/lexicon.csv,
data/prices_coffee_ucda.csv and data/prices_wfp_uga.csv (both written by ml/fetch_prices.py).
Schema: the CREATE TABLE statements below. The build fails on any rule violation, so a bad row never ships:
every advice/price row cites a source, en/sw SMS text is GSM-7 and <= 120 chars, long text <= 600 chars,
no virtual tables (Android's SQLite has no FTS5), journal_mode=DELETE, user_version=1.

Usage: python ml/build_knowledge.py
"""
import csv
import json
import sqlite3
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
OUT = ROOT / "android/app/src/main/assets/models/knowledge.sqlite"
SCHEMA_VERSION = 1
HOME = {"admin1": "Kween", "market": "Kapchorwa"}  # Mt Elgon Arabica belt, the stand-in for Ondera
LABELS = {  # contracts section 1, full target set
    "coffee_healthy", "coffee_rust", "coffee_miner", "coffee_cercospora", "coffee_phoma",
    "maize_healthy", "maize_fall_armyworm", "maize_streak_virus", "maize_lethal_necrosis", "maize_leaf_blight",
    "maize_leaf_spot", "bean_healthy", "bean_angular_leaf_spot", "bean_rust",
}
SLOTS = {"intent": {"diagnose", "price", "planting", "help", "other"}, "crop": {"coffee", "maize", "bean"}}
MATCH = {"token", "prefix", "substring"}
# GSM 03.38 basic character set (no extension table, so no escape characters that cost 2 septets).
GSM7 = set("@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
           "¿abcdefghijklmnopqrstuvwxyzäöñüà")

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE sources(id TEXT PRIMARY KEY, title TEXT NOT NULL, publisher TEXT, url TEXT, licence TEXT, accessed TEXT);
CREATE TABLE advice(label TEXT NOT NULL, lang TEXT NOT NULL, sms TEXT NOT NULL, long TEXT NOT NULL,
                    source_id TEXT NOT NULL REFERENCES sources(id), translation TEXT NOT NULL,
                    PRIMARY KEY(label, lang));
CREATE TABLE prices(id INTEGER PRIMARY KEY, commodity TEXT NOT NULL, country TEXT NOT NULL, admin1 TEXT, market TEXT,
                    pricetype TEXT, unit TEXT NOT NULL, currency TEXT NOT NULL,
                    price_low REAL NOT NULL, price_high REAL NOT NULL, date TEXT NOT NULL,
                    source_id TEXT NOT NULL REFERENCES sources(id), derived INTEGER NOT NULL DEFAULT 0);
CREATE TABLE lexicon(lang TEXT NOT NULL, term TEXT NOT NULL, slot TEXT NOT NULL, value TEXT NOT NULL,
                     match TEXT NOT NULL DEFAULT 'prefix');
CREATE INDEX prices_lookup ON prices(commodity, market, date);
-- Newest row per (commodity, market); market IS NULL = national figure.
CREATE VIEW latest_prices AS
  SELECT p.* FROM prices p
  WHERE p.date = (SELECT MAX(q.date) FROM prices q
                  WHERE q.commodity = p.commodity AND IFNULL(q.market, '') = IFNULL(p.market, ''));
"""


class BuildError(Exception):
    pass


def read_csv(name: str) -> list[dict]:
    with open(DATA / name, encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def check_sms(label: str, lang: str, text: str) -> None:
    bad = sorted(set(text) - GSM7)
    if bad:
        raise BuildError(f"advice {label}/{lang}: non-GSM-7 characters {bad} would force 70-char UCS-2 SMS")
    if len(text) > 120:
        raise BuildError(f"advice {label}/{lang}: sms is {len(text)} chars (max 120)")


def build_sources(sources: list[dict], coffee: list[dict]) -> list[tuple]:
    rows = [(s["id"], s["title"], s["publisher"], s["url"], s["licence"], s["accessed"]) for s in sources]
    for month in sorted({(r["date"], r["month_name"], r["url"]) for r in coffee}):
        rows.append((f"ucda-{month[0]}", f"Monthly Coffee Report, {month[1]} (national farm-gate averages)",
                     "MAAIF Coffee Department (formerly UCDA)", month[2], "Uganda government publication",
                     datetime.now(timezone.utc).date().isoformat()))
    return rows


def build_advice(advice: list[dict], source_ids: set[str]) -> list[tuple]:
    rows = []
    for item in advice:
        label = item["label"]
        if label not in LABELS:
            raise BuildError(f"advice label {label!r} is not a classifier label")
        if item["source_id"] not in source_ids:
            raise BuildError(f"advice {label}: unknown source {item['source_id']}")
        for lang, translation in (("en", "original"), ("sw", "machine")):
            text = item[lang]
            check_sms(label, lang, text["sms"])
            if len(text["long"]) > 600:
                raise BuildError(f"advice {label}/{lang}: long is {len(text['long'])} chars (max 600)")
            rows.append((label, lang, text["sms"], text["long"], item["source_id"], translation))
    return rows


def build_prices(coffee: list[dict], wfp: list[dict]) -> list[tuple]:
    # (commodity, country, admin1, market, pricetype, unit, currency, low, high, date, source_id, derived)
    rows = [(r["commodity"], "UG", None, None, "Farm-gate", "KG", "UGX", float(r["price"]), float(r["price"]),
             r["date"], f"ucda-{r['date']}", 0) for r in coffee]
    by_month = defaultdict(list)
    for r in wfp:
        rows.append((r["commodity"], "UG", r["admin1"], r["market"], "Retail", r["unit"], r["currency"],
                     float(r["price"]), float(r["price"]), r["date"], "wfp-hdx-uga", 0))
        by_month[(r["commodity"], r["date"], r["unit"], r["currency"])].append(float(r["price"]))
    for (commodity, date, unit, currency), values in sorted(by_month.items()):
        if len(values) < 4:
            continue
        q1, _, q3 = statistics.quantiles(values, n=4)
        rows.append((commodity, "UG", None, None, "Retail", unit, currency, round(q1), round(q3), date,
                     "wfp-hdx-uga-derived", 1))
    return rows


def build_lexicon(lexicon: list[dict]) -> list[tuple]:
    rows = []
    for r in lexicon:
        if r["match"] not in MATCH:
            raise BuildError(f"lexicon {r}: bad match")
        allowed = SLOTS.get(r["slot"])
        if allowed is not None and r["value"] not in allowed:
            raise BuildError(f"lexicon {r}: value not in {sorted(allowed)}")
        if r["slot"] == "symptom" and not any(label.endswith("_" + r["value"]) for label in LABELS):
            raise BuildError(f"lexicon {r}: symptom matches no classifier label")
        if r["term"] != r["term"].lower():
            raise BuildError(f"lexicon {r}: term must be lowercase")
        rows.append((r["lang"], r["term"], r["slot"], r["value"], r["match"]))
    return rows


def build() -> None:
    coffee, wfp = read_csv("prices_coffee_ucda.csv"), read_csv("prices_wfp_uga.csv")
    sources = build_sources(read_csv("sources.csv"), coffee)
    source_ids = {s[0] for s in sources}
    advice = build_advice(json.loads((DATA / "advice.json").read_text(encoding="utf-8")), source_ids)
    prices = build_prices(coffee, wfp)
    missing = {p[10] for p in prices} - source_ids
    if missing:
        raise BuildError(f"prices cite unknown sources {missing}")
    lexicon = build_lexicon(read_csv("lexicon.csv"))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    db = sqlite3.connect(tmp)
    db.executescript(SCHEMA)
    built_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    meta = {"schema_version": str(SCHEMA_VERSION), "built_at": built_at, "country": "UG", "currency": "UGX",
            "home_admin1": HOME["admin1"], "home_market": HOME["market"], "price_stale_days": "120"}
    db.executemany("INSERT INTO meta VALUES (?, ?)", meta.items())
    db.executemany("INSERT INTO sources VALUES (?, ?, ?, ?, ?, ?)", sources)
    db.executemany("INSERT INTO advice VALUES (?, ?, ?, ?, ?, ?)", advice)
    db.executemany("INSERT INTO prices (commodity, country, admin1, market, pricetype, unit, currency, price_low,"
                   " price_high, date, source_id, derived) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", prices)
    db.executemany("INSERT INTO lexicon VALUES (?, ?, ?, ?, ?)", lexicon)
    db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    db.commit()
    virtual = db.execute("SELECT name FROM sqlite_master WHERE sql LIKE '%VIRTUAL%'").fetchall()
    if virtual:
        raise BuildError(f"virtual tables are not allowed on Android: {virtual}")
    db.execute("PRAGMA journal_mode = DELETE")
    db.execute("VACUUM")
    db.close()
    tmp.replace(OUT)

    db = sqlite3.connect(OUT)
    print(f"wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB, built_at {built_at})")
    for table in ("sources", "advice", "prices", "lexicon"):
        print(f"  {table}: {db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]} rows")
    for row in db.execute("SELECT commodity, IFNULL(market, 'national') m, pricetype, price_low, price_high, date "
                          "FROM latest_prices WHERE market IS NULL OR market = ? ORDER BY commodity, m",
                          (HOME["market"],)):
        print("  latest:", row)
    db.close()


if __name__ == "__main__":
    build()
