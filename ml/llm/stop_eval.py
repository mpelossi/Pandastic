"""Does stopping generation after the symptom change what LlmNlu reads? (O4)

LlmNlu reads only intent, crop and symptom, the first fields of the JSON, so the app cuts ml/llm/slots.gbnf's root
rule right after the symptom, at the `,"` the model writes there anyway (LlmNlu.stopAfterSymptom), and closes the
object itself (LlmNlu.closeJson). This runs every SMS of the four eval sets with the full and with the cut grammar
(temperature 0, same prompt as eval_llm.py) and compares lang, intent, crop and symptom, plus generated tokens and
generation time.

Usage (from the repo root):
  python ml/llm/stop_eval.py --server path/to/llama-server --model path/to/model.gguf [--threads 4]
Writes ml/reports/llm_stop_after_symptom_{lora,base}.md and .json (lora = the fine-tuned file).
"""
import argparse
import json
import statistics
import subprocess
import time
from pathlib import Path

import requests

from eval_llm import ROOT, HERE, SETS, read

READ = ["lang", "intent", "crop", "symptom"]
AFTER_SYMPTOM = 'symptom ",\\"commodity\\":"'


def stop_after_symptom(grammar: str) -> str:
    """Same cut as LlmNlu.stopAfterSymptom: the root rule ends with the `,"` after the symptom."""
    start = grammar.index(AFTER_SYMPTOM)
    end = grammar.find("\n", start)
    return grammar[:start] + 'symptom ",\\""' + (grammar[end:] if end >= 0 else "")


def close_json(text: str) -> str:
    """Same as LlmNlu.closeJson."""
    return text[:-2] + "}" if text.endswith(',"') else text


def ask(base, system, grammar, text):
    body = {
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": f"SMS: {text}"}],
        "temperature": 0, "max_tokens": 80, "grammar": grammar, "cache_prompt": True,
        "chat_template_kwargs": {"enable_thinking": False},
    }
    r = requests.post(f"{base}/v1/chat/completions", json=body, timeout=120)
    r.raise_for_status()
    data = r.json()
    timings = data.get("timings", {})
    return data["choices"][0]["message"]["content"], timings.get("predicted_n", 0), timings.get("predicted_ms", 0.0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--port", type=int, default=8091)
    args = parser.parse_args()

    system = (HERE / "system_prompt.txt").read_text(encoding="utf-8").strip()
    full = (HERE / "slots.gbnf").read_text(encoding="utf-8")
    cut = stop_after_symptom(full)
    server = subprocess.Popen([str(Path(args.server).resolve()), "-m", str(Path(args.model).resolve()), "--port", str(args.port),
                               "-c", "2048", "-t", str(args.threads), "--jinja", "-np", "1"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = f"http://127.0.0.1:{args.port}"
    results, differences = {}, []
    try:
        for _ in range(180):
            try:
                if requests.get(f"{base}/health", timeout=2).status_code == 200:
                    break
            except requests.RequestException:
                pass
            time.sleep(1)
        for name, file in SETS.items():
            rows = read(HERE / file)
            same, tokens_full, tokens_cut, ms_full, ms_cut = 0, [], [], [], []
            for row in rows:
                text_full, n_full, t_full = ask(base, system, full, row["text"])
                text_cut, n_cut, t_cut = ask(base, system, cut, row["text"])
                a, b = json.loads(text_full), json.loads(close_json(text_cut))
                if all(a.get(k) == b.get(k) for k in READ):
                    same += 1
                else:
                    differences.append(f"{name} `{row['text']}`: full {[a.get(k) for k in READ]} cut {[b.get(k) for k in READ]}")
                tokens_full.append(n_full); tokens_cut.append(n_cut); ms_full.append(t_full); ms_cut.append(t_cut)
            results[name] = {
                "n": len(rows), "same_lang_intent_crop_symptom": same,
                "tokens_median": [statistics.median(tokens_full), statistics.median(tokens_cut)],
                "tokens_total": [sum(tokens_full), sum(tokens_cut)],
                "generation_ms_total": [round(sum(ms_full)), round(sum(ms_cut))],
            }
            print(name, json.dumps(results[name]))
    finally:
        server.terminate()

    n = sum(r["n"] for r in results.values())
    summary = {"model": Path(args.model).name, "threads": args.threads, "sms": n,
               "same": sum(r["same_lang_intent_crop_symptom"] for r in results.values()),
               "tokens_total": [sum(r["tokens_total"][i] for r in results.values()) for i in (0, 1)],
               "generation_ms_total": [sum(r["generation_ms_total"][i] for r in results.values()) for i in (0, 1)],
               "sets": results, "differences": differences}
    write_report(summary)


def write_report(summary: dict) -> None:
    name = "llm_stop_after_symptom_" + ("lora" if "pandastic" in summary["model"] else "base")
    reports = ROOT / "ml/reports"
    (reports / f"{name}.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    tok, ms = summary["tokens_total"], summary["generation_ms_total"]
    lines = [
        f"# LLM: stop generation after the symptom (O4), `{summary['model']}`", "",
        "LlmNlu reads only `intent`, `crop` and `symptom` from the model (`lang` comes before them, `commodity` and",
        "`offer` after). The app cuts the grammar's root rule after the symptom, at the `,\"` the model writes there",
        "anyway (`LlmNlu.stopAfterSymptom`), and closes the JSON in Java (`LlmNlu.closeJson`): `commodity` and `offer` are",
        "never generated, and every token before the cut is the one the full grammar gives (greedy decoding).", "",
        "Measured with `ml/llm/stop_eval.py`: every SMS of the four eval sets (synthetic, team-written), full and cut",
        f"grammar back to back per SMS, llama.cpp, temperature 0, {summary['threads']} CPU threads on a laptop, same prompt as",
        "`eval_llm.py`. Token counts are exact; times are noisy (the laptop was also building or running the emulator).", "",
        "| Set | SMS | same lang, intent, crop, symptom | tokens per SMS (median, full → cut) | generation time (total, full → cut) |",
        "| :-- | --: | --: | :-- | :-- |",
    ]
    for set_name, r in summary["sets"].items():
        lines.append(f"| {set_name} | {r['n']} | {r['same_lang_intent_crop_symptom']} | {r['tokens_median'][0]:g} → "
                     f"{r['tokens_median'][1]:g} | {r['generation_ms_total'][0] / 1000:.1f} s → {r['generation_ms_total'][1] / 1000:.1f} s |")
    lines += ["", f"**All sets: {summary['same']}/{summary['sms']} SMS read the same; generated tokens {tok[0]} → {tok[1]} "
              f"(−{1 - tok[1] / tok[0]:.0%}); generation time {ms[0] / 1000:.0f} s → {ms[1] / 1000:.0f} s (−{1 - ms[1] / ms[0]:.0%}).**",
              "The prompt (system prompt cached, then the SMS) is evaluated as before, so a whole call saves less than the",
              "generation share."]
    if summary["differences"]:
        lines += ["", "Differences:", ""] + [f"- {d}" for d in summary["differences"]]
    (reports / f"{name}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{summary['same']}/{summary['sms']} same; tokens {tok}; generation ms {ms}; wrote ml/reports/{name}.md")


if __name__ == "__main__":
    main()
