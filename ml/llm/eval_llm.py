"""Measure Qwen3.5-0.8B (llama.cpp, GBNF-constrained) against KeywordNlu on the synthetic SMS sets.

Starts llama-server with the GGUF, sends every SMS with ml/llm/system_prompt.txt and ml/llm/slots.gbnf,
temperature 0, thinking disabled, and scores the slots exactly like NluEvalTest (commodity = the crop's
default when a price question names none). Latency is measured on this computer's CPU; a phone is slower.

Sets: dev (used to tune the lexicon), heldout (written before any results), fresh (written after the LoRA was
trained, in phrasings unlike its templates; after its first measurement its errors were used to fix KeywordNlu),
fresh2 (written before those fixes and never used to make them: the honest test for keywords and fine-tunes).

Usage (from the repo root; first run NluEvalTest, which writes android/app/build/nlu-eval/kw_{dev,heldout,fresh}.csv):
  python ml/llm/eval_llm.py --server path/to/llama-server --model path/to/model.gguf
  python ml/llm/eval_llm.py --rescore --predictions-dir DIR     # score saved qwen_predictions_*.csv, no model
Writes ml/reports/<report-name>.md and .json (default nlu_eval; use e.g. --report-name nlu_eval_lora for a fine-tune).
"""
import argparse
import csv
import json
import statistics
import subprocess
import time
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SETS = {"dev": "eval_sms.csv", "heldout": "eval_sms_heldout.csv", "fresh": "eval_sms_fresh.csv", "fresh2": "eval_sms_fresh2.csv"}
KW_DIR = ROOT / "android/app/build/nlu-eval"
POLICIES = ("keyword", "qwen", "hybrid_intent", "hybrid_intent_crop", "hybrid_intent_symptom", "hybrid_no_offer",
            "llm_first", "hybrid_fill")
SLOTS = ["lang", "intent", "crop", "symptom", "commodity", "offer"]
DEFAULT_COMMODITY = {"coffee": "coffee_arabica_parchment", "maize": "maize_grain", "bean": "beans_dry"}


def read(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def effective(pred: dict) -> dict:
    out = {k: ("" if pred.get(k) is None else str(pred.get(k))) for k in SLOTS}
    if out["intent"] == "price":
        out["commodity"] = out["commodity"] or DEFAULT_COMMODITY.get(out["crop"], "")
    else:
        out["commodity"] = ""
    return out


def same_reply(gold: dict, pred: dict) -> bool:
    """Would the app send the same reply? Brain answers `help` and `other` with the same menu (Resolver.help), so
    those two intents are one; every other slot must match."""
    menu = lambda intent: "help" if intent in ("help", "other", "") else intent
    return menu(gold["intent"] or "") == menu(pred.get("intent") or "") and all(
        (gold[k] or "") == (pred.get(k) or "") for k in SLOTS if k != "intent")


def score(gold_rows, predictions) -> dict:
    correct = {k: 0 for k in SLOTS}
    exact, replies, misses = 0, 0, []
    for row in gold_rows:
        pred = predictions.get(row["id"])
        if pred is None:
            continue
        ok = True
        for k in SLOTS:
            if (row[k] or "") == (pred.get(k) or ""):
                correct[k] += 1
            else:
                ok = False
                misses.append(f"{row['id']} `{row['text']}` {k}: want `{row[k]}` got `{pred.get(k)}`")
        exact += ok
        replies += same_reply(row, pred)
    n = len(gold_rows)
    return {"n": n, **{k: round(correct[k] / n, 3) for k in SLOTS}, "all_slots": round(exact / n, 3),
            "same_reply": round(replies / n, 3), "misses": misses}


# Which empty keyword slots each hybrid lets the LLM fill. The intent is filled only when no intent keyword matched.
FILLS = {"hybrid_intent": {"intent"}, "hybrid_intent_crop": {"intent", "crop"},
         "hybrid_intent_symptom": {"intent", "symptom"}, "hybrid_no_offer": {"intent", "crop", "symptom"},
         "hybrid_fill": {"intent", "crop", "symptom", "offer"}}


def _labels() -> set:
    import sys
    sys.path.insert(0, str(HERE.parent))
    from leaf.config import P2_LABELS
    return set(P2_LABELS) | {"maize_lethal_necrosis"}


def hybrid(kw: dict, qwen: dict, fill) -> dict:
    """Keyword values always win; the LLM only fills the empty slots named in `fill` (a FILLS key or a set).
    A filled symptom must form a real label with the crop (as Resolver requires), or it is dropped."""
    fill = FILLS[fill] if isinstance(fill, str) else fill
    out = dict(kw)
    no_intent_evidence = kw.get("intent_prob", "0") in ("0", "0.0") and kw["intent"] == "other"
    if "intent" in fill and no_intent_evidence and qwen["intent"]:
        out["intent"] = qwen["intent"]
    for k in ("crop", "symptom", "offer"):
        if k in fill and not kw[k]:
            out[k] = qwen[k]
    if out["symptom"] and not kw["symptom"] and out["crop"] and f"{out['crop']}_{out['symptom']}" not in _labels():
        out["symptom"] = ""
    return effective({k: (out[k] or None) for k in SLOTS} | {"commodity": kw["commodity"] or None})


def llm_first(kw: dict, llm: dict) -> dict:
    """The LLM's intent and crop win whenever it gives them; lang, symptom and offer stay with the keywords.
    A commodity the SMS names (kiboko, drugar, ...) is kept if the crop did not change."""
    out = dict(kw)
    out["intent"] = llm["intent"] or kw["intent"]
    out["crop"] = llm["crop"] or kw["crop"]
    named = kw["commodity"] if kw["crop"] == out["crop"] and kw["commodity"] != DEFAULT_COMMODITY.get(kw["crop"]) else ""
    return effective({k: (out[k] or None) for k in SLOTS} | {"commodity": named or None})


def train_overlap(sets: dict, threshold: float = 0.6) -> dict:
    """How many eval SMS have a near-copy (token Jaccard >= threshold) among the LoRA's synthetic training SMS."""
    import re
    import sys
    sys.path.insert(0, str(HERE.parent))
    from llm import gen_train
    tokens = lambda text: set(re.findall(r"\w+", text.lower()))
    train = [tokens(r["sms"]) for r in gen_train.generate(3000)]
    near = lambda a: any(len(a & b) >= threshold * max(1, len(a | b)) for b in train)
    return {name: sum(near(tokens(r["text"])) for r in rows) for name, rows in sets.items()}


def ask(base: str, system: str, grammar: str, text: str) -> tuple[dict, float, dict]:
    body = {
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": f"SMS: {text}"}],
        "temperature": 0, "max_tokens": 80, "grammar": grammar, "cache_prompt": True,
        "chat_template_kwargs": {"enable_thinking": False},
    }
    started = time.perf_counter()
    r = requests.post(f"{base}/v1/chat/completions", json=body, timeout=120)
    r.raise_for_status()
    elapsed = time.perf_counter() - started
    data = r.json()
    content = data["choices"][0]["message"]["content"]
    return json.loads(content), elapsed, data.get("timings", {})


def write_report(results: dict, threads: int, report: str = "nlu_eval") -> None:
    reports = ROOT / "ml/reports"
    reports.mkdir(parents=True, exist_ok=True)
    (reports / f"{report}.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    lines = ["# SMS understanding: KeywordNlu vs Qwen3.5-0.8B (GBNF)", "",
             "Synthetic SMS written by the team (labelled synthetic). `dev` was used to tune the keyword lexicon;",
             "`heldout` was written before any results and never used for tuning; `fresh` was written after the LoRA was",
             "trained, in phrasings unlike its templates (first keyword score 68%; its errors were then used to fix",
             "KeywordNlu); `fresh2` was written before those fixes and never used to make them (keywords 80% before, the",
             "row below after). `fresh2` is the honest test. Qwen: Q4_K_M via llama.cpp,",
             f"temperature 0, thinking off, {threads} CPU threads on a laptop (a phone is slower).", "",
             "| Set | Model | n | lang | intent | crop | symptom | commodity | offer | all slots | same reply |",
             "| :-- | :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: |"]
    sets = {name: r for name, r in results.items() if name != "meta"}
    for name, r in sets.items():
        for model in POLICIES:
            if model in r:
                s = r[model]
                lines.append(f"| {name} | {model} | {s['n']} | " + " | ".join(f"{s[k]:.0%}" for k in SLOTS + ["all_slots"]) + f" | {s.get('same_reply', 0):.0%} |")
    lines += ["", "`same reply` treats `help` and `other` as one intent, because the app answers both with the same menu;",
              "it is the share of SMS that get exactly the reply the gold slots would give.",
              "", "Keyword slots always win in the hybrids. `hybrid_intent`: the LLM only supplies the intent when no intent",
              "keyword matched (KeywordNlu intentProb 0). `hybrid_intent_crop`: also the crop when none was found (LlmNlu",
              "as of 01:00 UTC); it names coffee/maize for crops we don't support (cassava, tomato, tea), which keywords",
              "correctly leave empty. `hybrid_intent_symptom`: intent + a missing symptom (it must fit the crop);",
              "`hybrid_no_offer`: intent + crop + symptom; `hybrid_fill`: every empty slot, offer too. The base model",
              "invents symptoms; the fine-tune much less.",
              "`llm_first` = the LLM's intent and crop win whenever it gives them; lang, symptom and offer stay with the keywords.", ""]
    meta = results.get("meta", {})
    if meta.get("model"):
        lines += [f"Model: `{meta['model']}`."]
    if meta.get("train_overlap"):
        lines += ["Near-copies of LoRA training SMS (token Jaccard >= 0.6 with one of the 3,000 synthetic SMS): " + ", ".join(
            f"{name} {k}/{sets[name]['qwen']['n']}" for name, k in meta["train_overlap"].items() if name in sets)
            + ". A fine-tuned model's dev/heldout scores are optimistic by that much; `fresh` is the honest one."]
    lines += ["",
              "| Set | median latency (s) | max (s) | prompt ms (median) | generation ms (median) |", "| :-- | --: | --: | --: | --: |"]
    for name, r in sets.items():
        lines.append(f"| {name} | {r['latency_s']['median']} | {r['latency_s']['max']} | {r['prompt_ms_median']} | {r['gen_ms_median']} |")
    for name, r in sets.items():
        for model in ("keyword", "qwen"):
            if model in r and r[model]["misses"]:
                lines += ["", f"## Misses: {name} / {model}", ""] + [f"- {m}" for m in r[model]["misses"]]
    (reports / f"{report}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote ml/reports/{report}.md")


def evaluate_model(server_path: str, model_path: str, threads: int = 4, port: int = 8089, save_predictions: bool = True,
                   predictions_dir: Path = None) -> dict:
    """Start llama-server with the GGUF, run both SMS sets through it, return per-set scores and latency."""
    system = (HERE / "system_prompt.txt").read_text(encoding="utf-8").strip()
    grammar = (HERE / "slots.gbnf").read_text(encoding="utf-8")
    sets = {name: read(HERE / file) for name, file in SETS.items()}
    server = subprocess.Popen([str(Path(server_path).resolve()), "-m", str(Path(model_path).resolve()), "--port", str(port), "-c", "2048",
                               "-t", str(threads), "--jinja", "-np", "1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = f"http://127.0.0.1:{port}"
    results = {}
    try:
        for _ in range(180):
            try:
                if requests.get(f"{base}/health", timeout=2).status_code == 200:
                    break
            except requests.RequestException:
                pass
            time.sleep(1)
        for name, rows in sets.items():
            predictions, latencies, prompt_ms, gen_ms, failures = {}, [], [], [], 0
            for row in rows:
                try:
                    pred, elapsed, timings = ask(base, system, grammar, row["text"])
                    predictions[row["id"]] = effective(pred)
                    latencies.append(elapsed)
                    prompt_ms.append(timings.get("prompt_ms", 0))
                    gen_ms.append(timings.get("predicted_ms", 0))
                except Exception as e:  # grammar makes this unlikely; count it rather than crash
                    failures += 1
                    predictions[row["id"]] = {k: "" for k in SLOTS}
                    print("failed:", row["text"], e)
            if save_predictions:
                out_dir = Path(predictions_dir) if predictions_dir else ROOT / "ml/reports"
                out_dir.mkdir(parents=True, exist_ok=True)
                with open(out_dir / f"qwen_predictions_{name}.csv", "w", newline="", encoding="utf-8") as f:
                    w = csv.DictWriter(f, fieldnames=["id"] + SLOTS)
                    w.writeheader()
                    for rid, pred in predictions.items():
                        w.writerow({"id": rid, **pred})
            results[name] = {
                "qwen": score(rows, predictions),
                "latency_s": {"median": round(statistics.median(latencies), 2), "max": round(max(latencies), 2)} if latencies else {},
                "prompt_ms_median": round(statistics.median(prompt_ms)) if prompt_ms else None,
                "gen_ms_median": round(statistics.median(gen_ms)) if gen_ms else None,
                "failures": failures,
            }
            print(name, json.dumps({k: v for k, v in results[name]["qwen"].items() if k != "misses"}), results[name]["latency_s"])
    finally:
        server.terminate()
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server")
    parser.add_argument("--model")
    parser.add_argument("--report-only", action="store_true", help="rewrite the .md from ml/reports/<report-name>.json")
    parser.add_argument("--report-name", default="nlu_eval", help="report file name in ml/reports (no extension)")
    parser.add_argument("--threads", type=int, default=4, help="4 threads ~ a mid-range phone's big cores")
    parser.add_argument("--port", type=int, default=8089)
    parser.add_argument("--keyword-dir", type=Path, default=KW_DIR,
                        help="where NluEvalTest wrote kw_{dev,heldout,fresh}.csv")
    parser.add_argument("--predictions-dir", type=Path, default=ROOT / "ml/reports",
                        help="where the model's qwen_predictions_*.csv go (or are read from, with --rescore)")
    parser.add_argument("--rescore", action="store_true",
                        help="score saved predictions from --predictions-dir without running a model (no llama-server)")
    args = parser.parse_args()
    if args.report_only:
        write_report(json.loads((ROOT / f"ml/reports/{args.report_name}.json").read_text(encoding="utf-8")), args.threads, args.report_name)
        return

    sets = {name: read(HERE / file) for name, file in SETS.items()}
    if args.rescore:
        # Keep the latency and model name measured by the run that produced the predictions (same report name).
        previous_path = ROOT / f"ml/reports/{args.report_name}.json"
        previous = json.loads(previous_path.read_text(encoding="utf-8")) if previous_path.exists() else {}
        results = {}
        for name, rows in sets.items():
            path = args.predictions_dir / f"qwen_predictions_{name}.csv"
            if path.exists():
                llm = {r["id"]: effective(r) for r in read(path)}
                timing = {k: previous.get(name, {}).get(k) for k in ("latency_s", "prompt_ms_median", "gen_ms_median")}
                results[name] = {"qwen": score(rows, llm), **{k: v if v is not None else {"median": None, "max": None}
                                                              if k == "latency_s" else v for k, v in timing.items()}}
        args.model = args.model or previous.get("meta", {}).get("model")
    else:
        results = evaluate_model(args.server, args.model, args.threads, args.port, predictions_dir=args.predictions_dir)
    results["meta"] = {"model": Path(args.model).name if args.model else str(args.predictions_dir),
                       "train_overlap": train_overlap(sets)}
    for name, rows in sets.items():
        kw_path = args.keyword_dir / f"kw_{name}.csv"
        if name not in results or not kw_path.exists():
            print(f"skip {name}: needs {kw_path} (run NluEvalTest) and the model's predictions")
            continue
        kw = {r["id"]: r for r in read(kw_path)}
        llm = {r["id"]: r for r in read(args.predictions_dir / f"qwen_predictions_{name}.csv")}
        results[name]["keyword"] = score(rows, kw)
        for policy in FILLS:
            results[name][policy] = score(rows, {i: hybrid(kw[i], llm[i], policy) for i in kw})
        results[name]["llm_first"] = score(rows, {i: llm_first(kw[i], llm[i]) for i in kw})
        print(name, {m: (results[name][m]["all_slots"], results[name][m]["same_reply"]) for m in POLICIES})

    write_report(results, args.threads, args.report_name)


if __name__ == "__main__":
    main()
