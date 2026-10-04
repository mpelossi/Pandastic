"""Download the published price sources and write the curated price CSVs in data/.

Sources (both public, cited per row in knowledge.sqlite):
  * Coffee farm-gate prices: MAAIF Coffee Department (formerly UCDA) monthly coffee reports, one PDF per month,
    https://ugandacoffee.go.ug/resource-center/reports/monthly-reports. Each report states the national average
    farm-gate price per kg for Robusta Kiboko, FAQ, Arabica parchment and Drugar.
  * Maize and bean retail prices: WFP food prices for Uganda on HDX (CC BY-IGO).

Outputs: data/prices_coffee_ucda.csv, data/prices_wfp_uga.csv. Raw downloads go to data/raw/ (git-ignored).
Usage: python ml/fetch_prices.py
"""
import csv
import re
from pathlib import Path

import requests
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "data/raw"
UA = {"User-Agent": "Mozilla/5.0 (pandastic hackathon research; contact via repo)"}
UCDA_SITE = "https://ugandacoffee.go.ug"
UCDA_LIST = UCDA_SITE + "/resource-center/reports/monthly-reports"
WFP_CSV = ("https://data.humdata.org/dataset/883929b1-521e-4834-97f5-0ccc2df75b89/resource/"
           "e082d683-cad5-4dcd-bf54-db76ae254d33/download/wfp_food_prices_uga.csv")
MONTHS = {m: i for i, m in enumerate(
    "JANUARY FEBRUARY MARCH APRIL MAY JUNE JULY AUGUST SEPTEMBER OCTOBER NOVEMBER DECEMBER".split(), 1)}
COFFEE = {  # label in the report sentence -> commodity code (contracts section 3)
    "Robusta Kiboko": "coffee_robusta_kiboko",
    "FAQ": "coffee_robusta_faq",
    "Arabica parchment": "coffee_arabica_parchment",
    "Drugar": "coffee_arabica_drugar",
}
WFP_COMMODITIES = {"Maize (white)": "maize_grain", "Beans": "beans_dry"}
WFP_SINCE = "2024-01"


def fetch(url: str, path: Path) -> Path:
    if not path.exists():
        response = requests.get(url, headers=UA, timeout=120)
        response.raise_for_status()
        path.write_bytes(response.content)
    return path


def coffee_rows() -> list[dict]:
    listing = requests.get(UCDA_LIST, headers=UA, timeout=60).text
    links = sorted(set(re.findall(r'href="(/sites/default/files/[^"]*\.pdf)"', listing)))
    rows = []
    for link in links:
        url = UCDA_SITE + link
        pdf = fetch(url, RAW / ("ucda_" + re.sub(r"[^A-Za-z0-9]+", "_", link.rsplit("/", 1)[-1])))
        text = re.sub(r"\s+", " ", " ".join((page.extract_text() or "") for page in PdfReader(pdf).pages))
        month = re.search(r"MONTHLY ?COFFEE ?REPORT ?[-–] ?([A-Z]+) ?(\d{4})", text, re.I)
        sentence = re.search(r"Farm[- ]?gate prices for.{0,260}?Drugar[^.]*\.", text, re.I)
        if not (month and sentence):
            print(f"skip (no farm-gate sentence): {link}")
            continue
        date = f"{month.group(2)}-{MONTHS[month.group(1).upper()]:02d}"
        for label, commodity in COFFEE.items():
            value = re.search(re.escape(label) + r"[^0-9]{0,25}?UGX ?([\d,]+)", sentence.group(0), re.I)
            if value:
                price = float(value.group(1).replace(",", ""))
                rows.append({"commodity": commodity, "date": date, "price": price, "url": url,
                             "month_name": f"{month.group(1).title()} {month.group(2)}",
                             "quote": sentence.group(0)})
    # The site sometimes lists the same month twice (re-uploads); keep the first report per month.
    unique = {}
    for r in rows:
        unique.setdefault((r["commodity"], r["date"]), r)
    return sorted(unique.values(), key=lambda r: (r["commodity"], r["date"]))


def wfp_rows() -> list[dict]:
    path = fetch(WFP_CSV, RAW / "wfp_food_prices_uga.csv")
    rows = []
    with open(path, encoding="utf-8") as handle:
        for r in csv.DictReader(handle):
            if r["date"].startswith("#") or r["date"][:7] < WFP_SINCE:
                continue
            if r["commodity"] in WFP_COMMODITIES and r["pricetype"] == "Retail":
                rows.append({"commodity": WFP_COMMODITIES[r["commodity"]], "date": r["date"][:7],
                             "admin1": r["admin1"], "market": r["market"], "unit": r["unit"],
                             "currency": r["currency"], "price": float(r["price"])})
    return sorted(rows, key=lambda r: (r["commodity"], r["market"], r["date"]))


def write(path: Path, rows: list[dict]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {path.relative_to(ROOT)} ({len(rows)} rows)")


if __name__ == "__main__":
    RAW.mkdir(parents=True, exist_ok=True)
    write(ROOT / "data/prices_coffee_ucda.csv", coffee_rows())
    write(ROOT / "data/prices_wfp_uga.csv", wfp_rows())
