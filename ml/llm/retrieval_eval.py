"""Would a vector database (retrieval) help Qwen read farmer SMS? Measured, not assumed.

Qwen in the app only fills slots under a grammar; advice comes from an exact, label-keyed SQLite lookup. So the
place retrieval could help is the NLU: find the labelled SMS most similar to a new one and show them to Qwen as
examples (retrieval-augmented few-shot). The labelled bank is the 3,000 synthetic training SMS (gen_train).

1. Retrieval quality: for each eval SMS, do the nearest bank SMS carry the right intent / crop / symptom?
   Retrievers: BM25 over words + character 4-grams (no model, Swahili morphology), multilingual-e5-small
   (118 M parameters, SW+EN), and Qwen's own embeddings from the same GGUF (no extra weights).
2. End to end: Qwen with the k retrieved examples in the prompt vs without (predictions saved for eval_llm.py).
3. Cost: index size, embedding model size, extra prompt tokens and latency.

Usage (from the repo root):
  python ml/llm/retrieval_eval.py quality [--e5] [--qwen-embed --server S --model M]
  python ml/llm/retrieval_eval.py fewshot --server S --model M --k 4 --out ml/reports/rag_base
then: python ml/llm/eval_llm.py --rescore --predictions-dir ml/reports/rag_base --report-name nlu_eval_rag_base
"""
import argparse
import json
import math
import re
import statistics
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np
import requests

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
from llm import eval_llm, gen_train  # noqa: E402

SETS = {"heldout": "eval_sms_heldout.csv", "fresh": "eval_sms_fresh.csv", "fresh2": "eval_sms_fresh2.csv"}


def bank() -> list[dict]:
    return gen_train.generate(3000)


def norm(text: str) -> str:
    return re.sub(r"[^a-z0-9?]+", " ", re.sub(r"(?<=\d)[,.](?=\d{3}(?!\d))", "", text.lower())).strip()


def features(text: str) -> list[str]:
    """Words plus character 4-grams inside words, so 'yanakufa' and 'kufa' share features."""
    words = norm(text).split()
    grams = [w[i:i + 4] for w in words if len(w) > 4 for i in range(len(w) - 3)]
    return words + ["#" + g for g in grams]


class BM25:
    def __init__(self, docs: list[str], k1=1.2, b=0.75):
        self.docs = [Counter(features(d)) for d in docs]
        self.lengths = [sum(d.values()) for d in self.docs]
        self.avg = sum(self.lengths) / len(self.lengths)
        df = Counter(t for d in self.docs for t in d)
        n = len(self.docs)
        self.idf = {t: math.log(1 + (n - c + 0.5) / (c + 0.5)) for t, c in df.items()}
        self.k1, self.b = k1, b

    def search(self, query: str, k: int) -> list[int]:
        q = set(features(query))
        scores = np.zeros(len(self.docs))
        for i, (d, length) in enumerate(zip(self.docs, self.lengths)):
            s = 0.0
            for t in q:
                f = d.get(t)
                if f:
                    s += self.idf[t] * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * length / self.avg))
            scores[i] = s
        return list(np.argsort(-scores)[:k])


class Dense:
    """Cosine search over normalised embeddings: a flat matrix is enough for 3,000 rows (no index engine)."""
    def __init__(self, embed, docs: list[str]):
        self.embed = embed
        self.matrix = embed(docs)

    def search(self, query: str, k: int) -> list[int]:
        return list(np.argsort(-(self.matrix @ self.embed([query])[0]))[:k])


def e5_embedder():
    import torch
    from transformers import AutoModel, AutoTokenizer
    name = "intfloat/multilingual-e5-small"
    tok, model = AutoTokenizer.from_pretrained(name), AutoModel.from_pretrained(name).eval()
    params = sum(p.numel() for p in model.parameters())
    print(f"e5-small: {params / 1e6:.0f} M parameters, {params * 4 / 1e6:.0f} MB fp32, ~{params / 1e6:.0f} MB int8")

    @torch.no_grad()
    def embed(texts):
        out = []
        for i in range(0, len(texts), 64):
            batch = tok(["query: " + t for t in texts[i:i + 64]], padding=True, truncation=True, max_length=64, return_tensors="pt")
            h = model(**batch).last_hidden_state
            mask = batch["attention_mask"].unsqueeze(-1)
            v = (h * mask).sum(1) / mask.sum(1)
            out.append(torch.nn.functional.normalize(v, dim=-1).numpy())
        return np.concatenate(out)
    return embed


def start_server(server: str, model: str, port: int, extra: list[str]):
    proc = subprocess.Popen([str(Path(server).resolve()), "-m", str(Path(model).resolve()), "--port", str(port), "-c", "4096",
                             "-t", "4", "--jinja", "-np", "1", *extra], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(180):
        try:
            if requests.get(f"http://127.0.0.1:{port}/health", timeout=2).status_code == 200:
                return proc
        except requests.RequestException:
            pass
        time.sleep(1)
    proc.terminate()
    raise RuntimeError("llama-server did not start")


def qwen_embedder(port: int):
    def embed(texts):
        out = []
        for t in texts:
            r = requests.post(f"http://127.0.0.1:{port}/embedding", json={"content": t}, timeout=60).json()
            v = np.array(r[0]["embedding"] if isinstance(r, list) else r["embedding"], dtype=np.float32).reshape(-1)
            out.append(v / (np.linalg.norm(v) or 1))
        return np.stack(out)
    return embed


def quality(retrievers: dict, rows: list[dict]) -> None:
    sets = {name: eval_llm.read(HERE / f) for name, f in SETS.items()}
    print("top-1 neighbour has the right slot (intent / crop / symptom), and top-4 majority intent:")
    for rname, r in retrievers.items():
        line = []
        for name, gold in sets.items():
            ok = Counter()
            for g in gold:
                hits = r.search(g["text"], 4)
                top = rows[hits[0]]["slots"]
                ok["intent"] += top["intent"] == g["intent"]
                ok["crop"] += (top["crop"] or "") == (g["crop"] or "")
                ok["symptom"] += (top["symptom"] or "") == (g["symptom"] or "")
                majority = Counter(rows[h]["slots"]["intent"] for h in hits).most_common(1)[0][0]
                ok["intent4"] += majority == g["intent"]
            n = len(gold)
            line.append(f"{name} {ok['intent'] / n:.2f}/{ok['crop'] / n:.2f}/{ok['symptom'] / n:.2f} (maj4 {ok['intent4'] / n:.2f})")
        print(f"  {rname:12s} " + "  ".join(line))


def fewshot(server: str, model: str, k: int, out: Path, port: int = 8091) -> None:
    rows = bank()
    retriever = BM25([r["sms"] for r in rows])
    system = (HERE / "system_prompt.txt").read_text(encoding="utf-8").strip()
    grammar = (HERE / "slots.gbnf").read_text(encoding="utf-8")
    proc = start_server(server, model, port, [])
    out.mkdir(parents=True, exist_ok=True)
    base = f"http://127.0.0.1:{port}"
    try:
        for name, file in eval_llm.SETS.items():
            gold = eval_llm.read(HERE / file)
            preds, latencies, prompt_tokens = {}, [], []
            for g in gold:
                examples = "\n".join(f"SMS: {rows[h]['sms']}\nJSON: {gen_train.target(rows[h]['slots'])}"
                                     for h in retriever.search(g["text"], k))
                user = f"Similar SMS already read correctly:\n{examples}\n\nNow read this one.\nSMS: {g['text']}"
                body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                        "temperature": 0, "max_tokens": 80, "grammar": grammar, "cache_prompt": True,
                        "chat_template_kwargs": {"enable_thinking": False}}
                started = time.perf_counter()
                data = requests.post(f"{base}/v1/chat/completions", json=body, timeout=120).json()
                latencies.append(time.perf_counter() - started)
                prompt_tokens.append(data.get("usage", {}).get("prompt_tokens", 0))
                try:
                    preds[g["id"]] = eval_llm.effective(json.loads(data["choices"][0]["message"]["content"]))
                except (KeyError, ValueError):
                    preds[g["id"]] = {s: "" for s in eval_llm.SLOTS}
            with open(out / f"qwen_predictions_{name}.csv", "w", newline="", encoding="utf-8") as f:
                import csv
                w = csv.DictWriter(f, fieldnames=["id"] + eval_llm.SLOTS)
                w.writeheader()
                for rid, p in preds.items():
                    w.writerow({"id": rid, **p})
            s = eval_llm.score(gold, preds)
            print(f"{name}: qwen+{k} examples all_slots {s['all_slots']:.2f} | median {statistics.median(latencies):.2f} s, "
                  f"prompt tokens median {statistics.median(prompt_tokens):.0f}")
    finally:
        proc.terminate()


def knn(use_e5: bool, out: Path) -> None:
    """Retrieval as the NLU, no LLM: the nearest bank SMS's slots are the prediction (scored by eval_llm --rescore)."""
    import csv
    rows = bank()
    docs = [r["sms"] for r in rows]
    retriever = Dense(e5_embedder(), docs) if use_e5 else BM25(docs)
    out.mkdir(parents=True, exist_ok=True)
    for name, file in eval_llm.SETS.items():
        gold = eval_llm.read(HERE / file)
        with open(out / f"qwen_predictions_{name}.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["id"] + eval_llm.SLOTS)
            w.writeheader()
            for g in gold:
                w.writerow({"id": g["id"], **eval_llm.effective(rows[retriever.search(g["text"], 1)[0]]["slots"])})
    print(f"wrote nearest-neighbour predictions to {out}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["quality", "fewshot", "knn"])
    parser.add_argument("--e5", action="store_true")
    parser.add_argument("--qwen-embed", action="store_true")
    parser.add_argument("--server")
    parser.add_argument("--model")
    parser.add_argument("--k", type=int, default=4)
    parser.add_argument("--out", type=Path, default=ROOT / "ml/reports/rag_base")
    args = parser.parse_args()
    if args.mode == "fewshot":
        fewshot(args.server, args.model, args.k, args.out)
        return
    if args.mode == "knn":
        knn(args.e5, args.out)
        return
    rows = bank()
    docs = [r["sms"] for r in rows]
    started = time.perf_counter()
    retrievers = {"bm25": BM25(docs)}
    print(f"bm25 index over {len(docs)} SMS built in {time.perf_counter() - started:.1f} s")
    if args.e5:
        started = time.perf_counter()
        retrievers["e5-small"] = Dense(e5_embedder(), docs)
        m = retrievers["e5-small"].matrix
        print(f"e5 index {m.shape} = {m.nbytes / 1e6:.1f} MB fp32, built in {time.perf_counter() - started:.0f} s")
    proc = None
    if args.qwen_embed:
        proc = start_server(args.server, args.model, 8092, ["--embeddings", "--pooling", "mean"])
        started = time.perf_counter()
        retrievers["qwen-embed"] = Dense(qwen_embedder(8092), docs)
        m = retrievers["qwen-embed"].matrix
        print(f"qwen index {m.shape} = {m.nbytes / 1e6:.1f} MB fp32, built in {time.perf_counter() - started:.0f} s")
    try:
        quality(retrievers, rows)
    finally:
        if proc:
            proc.terminate()


if __name__ == "__main__":
    main()
