"""Generate SYNTHETIC SMS + gold slots for the Qwen LoRA (labelled synthetic in every row).

Templates in Swahili, English and mixed, with menu codes, typos, number formats (12000, 12,000, 12k,
elfu 12, Swahili number words) and the symptom phrasings of the label set. Every row is checked against
the grammar's enums, and texts that appear in the eval sets are removed, so evaluation stays honest.

Usage: python -m llm.gen_train --n 3000 --out ml/artifacts/llm/train.jsonl   (from ml/)
"""
import argparse
import csv
import json
import random
from pathlib import Path

from .make_grammar import COMMODITIES, CROPS, INTENTS, SYMPTOMS

HERE = Path(__file__).resolve().parent
CROP_WORDS = {
    "coffee": {"sw": ["kahawa", "buni", "kahawa yangu", "kahawa ya arabica", "kahwa"], "en": ["coffee", "my coffee", "arabica", "the coffee"], "code": "1"},
    "maize": {"sw": ["mahindi", "mahindi yangu", "mahind"], "en": ["maize", "corn", "my maize", "the maize"], "code": "2"},
    "bean": {"sw": ["maharage", "maharagwe", "maharage yangu"], "en": ["beans", "bean", "my beans"], "code": "3"},
}
SYMPTOM_PHRASES = {  # symptom -> (crops it applies to, sw phrases, en phrases)
    "rust": (["coffee", "bean"], ["unga wa njano chini ya majani", "kutu", "madoa ya machungwa", "vumbi la njano", "unga wa rangi ya chungwa"],
             ["orange powder under the leaves", "yellow powder on the leaves", "rust", "rusty orange spots", "powder under the leaf"]),
    "miner": (["coffee"], ["mchimba majani", "njia nyeupe ndani ya jani", "michirizi ndani ya majani", "mdudu ndani ya jani"],
              ["leaf miner", "white trails inside the leaf", "tunnels inside the leaves", "pale lines inside the leaf"]),
    "cercospora": (["coffee"], ["madoa ya kahawia yenye kati ya kijivu", "doa la jicho", "madoa ya mviringo ya kahawia"],
                   ["brown spots with a grey centre", "brown eye spot", "round brown spots"]),
    "phoma": (["coffee"], ["madoa meusi baada ya upepo baridi", "ncha za matawi zimekauka", "madoa meusi kwenye majani machanga"],
              ["black spots after the cold wind", "dry shoot tips", "dieback on the branches"]),
    "fall_armyworm": (["maize"], ["viwavijeshi", "viwavi", "funza", "matundu kwenye majani", "viwavi jeshi"],
                      ["armyworms", "caterpillars", "worms eating the leaves", "holes in the leaves", "fall armyworm"]),
    "streak_virus": (["maize"], ["mistari ya njano", "michirizi ya njano kwenye majani", "mistari mieupe na njano"],
                     ["yellow stripes", "streaks on the leaves", "thin yellow lines on the leaves"]),
    "lethal_necrosis": (["maize"], ["majani yanakauka kuanzia pembeni", "mimea inakufa yote"],
                        ["leaves drying from the edges", "whole plants dying"]),
    "leaf_blight": (["maize"], ["madoa marefu ya kijivu", "ukungu kwenye majani"], ["long grey spots", "leaf blight", "long brown patches"]),
    "leaf_spot": (["maize"], ["madoa madogo ya kijivu"], ["small grey rectangular spots", "grey leaf spot"]),
    "angular_leaf_spot": (["bean"], ["madoa ya pembe", "madoa yenye pembe"], ["angular spots", "angular brown spots"]),
}
GENERIC_PROBLEM = {"sw": ["yanageuka njano", "yanakufa", "yana ugonjwa", "yana shida", "yananyauka", "yana wadudu"],
                   "en": ["look sick", "are dying", "have a problem", "have insects", "are turning yellow"]}
DIAGNOSE = {
    "sw": ["majani ya {crop} yana {s}", "{crop} ina {s}", "nimeona {s} kwenye {crop}", "{crop} imeshambuliwa na {s} nifanye nini",
           "{code} {s}", "shamba la {crop} lina {s}", "naomba msaada {crop} yana {s}"],
    "en": ["my {crop} has {s}", "{s} on my {crop} leaves", "what to do, {crop} leaves have {s}", "{code} {s}",
           "help, {s} in the {crop}", "i see {s} on {crop}"],
}
GENERIC_DIAGNOSE = {"sw": ["majani ya {crop} {g}", "{crop} {g}", "mimea ya {crop} {g}"], "en": ["my {crop} leaves {g}", "the {crop} plants {g}"]}
COMMODITY_WORDS = {"coffee_robusta_kiboko": ["kiboko"], "coffee_robusta_faq": ["FAQ", "faq"], "coffee_arabica_drugar": ["drugar"],
                   "coffee_arabica_parchment": ["parchment", "arabica parchment"]}
PRICE = {
    "sw": ["bei ya {crop} leo", "bei ya {crop} ni ngapi", "mnunuzi wa {crop} anasema {o}", "{crop} {o}", "P {code} {o}", "p{code} {o}",
           "ninauza {crop} kwa {o}", "nauza {crop} kesho bei gani", "mnunuzi ananipa {o} kwa kilo ya {crop}", "{crop} kilo moja ni shilingi ngapi",
           "bei ya {crop} sokoni", "bei ya {crop} {o} ni nzuri?"],
    "en": ["{crop} price today", "buyer offers {o} for my {crop}", "is {o} fair for {crop}", "selling {crop} at {o}",
           "how much is {crop} per kilo", "trader says {o} per kg {crop} ok?", "{crop} {o}", "P {code} {o}"],
}
PRICE_NO_CROP = {"sw": ["bei gani", "mnunuzi amekuja na {o}", "anatoa {o} kwa kilo", "bei ya leo"], "en": ["what price", "buyer offers {o}", "price today"]}
PLANTING = {"sw": ["nipande {crop} lini", "msimu wa kupanda {crop}", "mvua imeanza nipande {crop} sasa", "ni lini nipande {crop}"],
            "en": ["when to plant {crop}", "best time to sow {crop}", "when should i plant {crop} this season"]}
HELP = {"sw": ["habari", "msaada", "nisaidie", "jambo", "mambo", "salaam", "?", "menyu", "habari za leo"], "en": ["hi", "hello", "help", "menu", "?", "good morning"]}
OTHER = {"sw": ["asante", "sawa", "nimeelewa", "mvua ni nyingi leo", "tutaonana", "asante sana"], "en": ["thanks", "ok", "thank you", "see you", "noted"]}
SW_UNITS = ["", "moja", "mbili", "tatu", "nne", "tano", "sita", "saba", "nane", "tisa"]
SW_TENS = ["", "kumi", "ishirini", "thelathini", "arobaini", "hamsini", "sitini", "sabini", "themanini", "tisini"]


def swahili_words(n: int) -> str:
    """Swahili number words for n < 100,000, multiplier first: 12500 -> 'elfu kumi na mbili na mia tano'."""
    def below_hundred(x):
        t, u = divmod(x, 10)
        if t and u:
            return f"{SW_TENS[t]} na {SW_UNITS[u]}"
        return SW_TENS[t] or SW_UNITS[u]
    parts = []
    thousands, rest = divmod(n, 1000)
    if thousands:
        parts.append("elfu " + below_hundred(thousands))
    hundreds, small = divmod(rest, 100)
    if hundreds:
        parts.append("mia " + SW_UNITS[hundreds])
    if small:
        parts.append(below_hundred(small))
    return " na ".join(parts)


def offer_text(rng: random.Random, crop: str, lang: str):
    value = {"coffee": rng.choice([5000, 5500, 6000, 11000, 11500, 12000, 12500, 13000, 14000, 15000, 15500, 16000]),
             "maize": rng.choice([700, 800, 900, 1000, 1100, 1200, 1500]),
             "bean": rng.choice([2000, 2200, 2500, 2800, 3000, 3500, 4000])}.get(crop) or rng.choice([900, 2500, 12000])
    style = rng.random()
    if style < 0.35:
        text = str(value)
    elif style < 0.55:
        text = f"{value:,}"
    elif style < 0.7 and value % 1000 == 0:
        text = f"{value // 1000}k"
    elif style < 0.8 and value % 1000 == 0 and lang == "sw":
        text = rng.choice([f"elfu {value // 1000}", f"{value // 1000} elfu"])
    elif lang == "sw":
        text = swahili_words(value)
    else:
        text = str(value)
    return text, value


def noisy(rng: random.Random, text: str) -> str:
    r = rng.random()
    if r < 0.15:
        text = text.upper() if rng.random() < 0.3 else text.capitalize()
    if rng.random() < 0.15:
        text += rng.choice(["?", "!", "??", " pls", " tafadhali" if "a" in text else " please"])
    return text


def slots(lang, intent, crop=None, symptom=None, commodity=None, offer=None) -> dict:
    return {"lang": lang, "intent": intent, "crop": crop, "symptom": symptom, "commodity": commodity, "offer": offer}


def example(rng: random.Random) -> tuple[str, dict]:
    lang = "sw" if rng.random() < 0.65 else "en"
    intent = rng.choices(["diagnose", "price", "planting", "help", "other"], [0.42, 0.33, 0.08, 0.1, 0.07])[0]
    crop = rng.choice(CROPS)
    words = CROP_WORDS[crop]
    crop_word = rng.choice(words[lang] + ([rng.choice(words["en"])] if lang == "sw" and rng.random() < 0.15 else []))  # code-switching
    if intent == "diagnose":
        if rng.random() < 0.8:
            symptom = rng.choice([s for s, (crops, _, _) in SYMPTOM_PHRASES.items() if crop in crops])
            phrase = rng.choice(SYMPTOM_PHRASES[symptom][1 if lang == "sw" else 2])
            template = rng.choice(DIAGNOSE[lang])
            if "{code}" in template:
                crop_word = words["code"]
            return template.format(crop=crop_word, s=phrase, code=words["code"]), slots(lang, "diagnose", crop, symptom)
        return rng.choice(GENERIC_DIAGNOSE[lang]).format(crop=crop_word, g=rng.choice(GENERIC_PROBLEM[lang])), slots(lang, "diagnose", crop)
    if intent == "price":
        if rng.random() < 0.12:
            o, value = offer_text(rng, "", lang)
            template = rng.choice(PRICE_NO_CROP[lang])
            return template.format(o=o), slots(lang, "price", offer=value if "{o}" in template else None)
        commodity = {"coffee": "coffee_arabica_parchment", "maize": "maize_grain", "bean": "beans_dry"}[crop]
        if crop == "coffee" and rng.random() < 0.3:
            commodity = rng.choice(list(COMMODITY_WORDS))
            crop_word = rng.choice(COMMODITY_WORDS[commodity])
        o, value = offer_text(rng, crop, lang)
        template = rng.choice(PRICE[lang])
        return template.format(crop=crop_word, o=o, code=words["code"]), slots(lang, "price", crop, None, commodity, value if "{o}" in template else None)
    if intent == "planting":
        return rng.choice(PLANTING[lang]).format(crop=crop_word), slots(lang, "planting", crop)
    if intent == "help":
        return rng.choice(HELP[lang]), slots(lang, "help")
    return rng.choice(OTHER[lang]), slots(lang, "other")


def valid(s: dict) -> bool:
    return (s["intent"] in INTENTS and (s["crop"] is None or s["crop"] in CROPS) and (s["symptom"] is None or s["symptom"] in SYMPTOMS)
            and (s["commodity"] is None or s["commodity"] in COMMODITIES))


def generate(n: int, seed: int = 7) -> list[dict]:
    held = set()
    for name in ("eval_sms.csv", "eval_sms_heldout.csv"):
        with open(HERE / name, newline="", encoding="utf-8") as f:
            held |= {r["text"].strip().lower() for r in csv.DictReader(f)}
    rng, seen, rows = random.Random(seed), set(), []
    while len(rows) < n:
        text, gold = example(rng)
        text = noisy(rng, text)
        key = text.strip().lower()
        if key in held or key in seen or not valid(gold):
            continue
        seen.add(key)
        rows.append({"sms": text, "slots": gold, "source": "synthetic (Pandastic templates)"})
    return rows


def target(gold: dict) -> str:
    """The assistant reply in the exact compact format that slots.gbnf allows."""
    return json.dumps(gold, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=3000)
    parser.add_argument("--out", type=Path, default=HERE.parent / "artifacts/llm/train.jsonl")
    args = parser.parse_args()
    rows = generate(args.n)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    from collections import Counter
    print(f"wrote {len(rows)} synthetic rows to {args.out}")
    print(Counter(r["slots"]["intent"] for r in rows), Counter(r["slots"]["lang"] for r in rows))
    for r in rows[:6]:
        print(" ", r["sms"], "->", target(r["slots"]))
