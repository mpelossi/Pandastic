"""LoRA fine-tune of Qwen3.5-0.8B for SMS slot filling.

Input: synthetic SMS from llm.gen_train (labelled synthetic). Target: the exact compact JSON slots.gbnf allows,
so the fine-tune and the on-device grammar agree. LoRA is attached only to the language model's linear layers
(the vision tower is unused: photo confidence comes from the calibrated leaf classifier). After training the
adapter is merged, converted to GGUF with llama.cpp's convert_hf_to_gguf.py and quantised to Q4_K_M, which is
the same file format and size the app already side-loads.

Local CPU smoke test (2 steps, then merge + save):  python -m llm.train_lora --smoke   (from ml/)
GPU run: modal run modal_lora.py   (see that file)
"""
import argparse
import json
import math
import random
import re
import subprocess
import sys
import time
from pathlib import Path

import torch

HERE = Path(__file__).resolve().parent
SYSTEM = (HERE / "system_prompt.txt").read_text(encoding="utf-8").strip()


def load(model_dir: str, dtype):
    from transformers import AutoModelForImageTextToText, AutoTokenizer
    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForImageTextToText.from_pretrained(model_dir, dtype=dtype)
    return tokenizer, model


def lora_targets(model) -> str:
    """Regex over every nn.Linear inside the language model (attention, linear-attention and MLP projections)."""
    names = {name.rsplit(".", 1)[-1] for name, module in model.named_modules()
             if isinstance(module, torch.nn.Linear) and "language_model" in name and "lm_head" not in name}
    return r".*language_model.*\.(" + "|".join(sorted(map(re.escape, names))) + r")$"


def encode(tokenizer, sms: str, target: str, max_len: int):
    messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": f"SMS: {sms}"}]
    prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    prompt_ids = tokenizer(prompt, add_special_tokens=False)["input_ids"]
    answer_ids = tokenizer(target + "<|im_end|>\n", add_special_tokens=False)["input_ids"]
    ids = (prompt_ids + answer_ids)[:max_len]
    labels = ([-100] * len(prompt_ids) + answer_ids)[:max_len]  # loss only on the JSON answer
    return ids, labels


def batches(examples, batch_size, pad_id, rng):
    order = list(range(len(examples)))
    rng.shuffle(order)
    for start in range(0, len(order), batch_size):
        chunk = [examples[i] for i in order[start:start + batch_size]]
        width = max(len(ids) for ids, _ in chunk)
        input_ids = torch.full((len(chunk), width), pad_id)
        labels = torch.full((len(chunk), width), -100)
        attention = torch.zeros((len(chunk), width), dtype=torch.long)
        for row, (ids, lab) in enumerate(chunk):
            input_ids[row, :len(ids)] = torch.tensor(ids)
            labels[row, :len(lab)] = torch.tensor(lab)
            attention[row, :len(ids)] = 1
        yield input_ids, labels, attention


def train(model_dir: str, rows: list, out_dir: Path, epochs=2, batch_size=4, accumulate=4, lr=2e-4, rank=16, alpha=32,
          max_len=1024, max_steps=None, device=None, log=print) -> Path:
    from peft import LoraConfig, get_peft_model
    from .gen_train import target
    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    dtype = torch.bfloat16 if device == "cuda" else torch.float32
    tokenizer, model = load(model_dir, dtype)
    if device == "cuda":
        # Without flash-linear-attention, Qwen3.5's gated-delta layers keep every chunk state for backward:
        # batch 8 x ~700 tokens ran a 22 GB L4 out of memory. Recompute activations instead.
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
        model.enable_input_require_grads()
    config = LoraConfig(r=rank, lora_alpha=alpha, lora_dropout=0.05, target_modules=lora_targets(model), task_type="CAUSAL_LM")
    model = get_peft_model(model, config).to(device)
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    log(f"LoRA r={rank}: {trainable / 1e6:.1f}M trainable parameters on {device}")
    examples = [encode(tokenizer, r["sms"], target(r["slots"]), max_len) for r in rows]
    # The system prompt alone is ~650 tokens: an example cut before its answer has no loss tokens (NaN loss).
    examples = [e for e in examples if any(label != -100 for label in e[1])]
    if not examples:
        raise ValueError(f"max_len={max_len} cuts off every answer; raise it")
    pad_id = tokenizer.pad_token_id if tokenizer.pad_token_id is not None else tokenizer.eos_token_id
    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr, weight_decay=0.0)
    total = epochs * math.ceil(len(examples) / (batch_size * accumulate))
    if max_steps:
        total = min(total, max_steps)
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, lambda s: min(1.0, (s + 1) / max(1, total // 20)) * 0.5 * (1 + math.cos(math.pi * s / max(1, total))))
    rng, step, started = random.Random(0), 0, time.time()
    model.train()
    for epoch in range(epochs):
        for i, (ids, labels, attention) in enumerate(batches(examples, batch_size, pad_id, rng)):
            with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=device == "cuda"):
                loss = model(input_ids=ids.to(device), attention_mask=attention.to(device), labels=labels.to(device)).loss / accumulate
            loss.backward()
            if (i + 1) % accumulate == 0:
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
                scheduler.step()
                optimizer.zero_grad(set_to_none=True)
                step += 1
                if step % 10 == 0 or step == 1:
                    log(f"epoch {epoch + 1} step {step}/{total} loss {loss.item() * accumulate:.4f} ({time.time() - started:.0f}s)")
                if max_steps and step >= max_steps:
                    break
        if max_steps and step >= max_steps:
            break
    out_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(out_dir / "adapter")
    merged = model.merge_and_unload()
    merged.save_pretrained(out_dir / "merged", safe_serialization=True)
    tokenizer.save_pretrained(out_dir / "merged")
    for extra in ("chat_template.jinja", "preprocessor_config.json", "video_preprocessor_config.json"):
        if (Path(model_dir) / extra).exists():
            (out_dir / "merged" / extra).write_bytes((Path(model_dir) / extra).read_bytes())
    restore_untrained_tensors(Path(model_dir), out_dir / "merged", log)
    (out_dir / "training.json").write_text(json.dumps({"rows": len(rows), "steps": step, "epochs": epochs, "rank": rank,
                                                         "alpha": alpha, "lr": lr, "base": model_dir, "data": "synthetic"}, indent=2))
    log(f"saved adapter + merged model to {out_dir}")
    return out_dir / "merged"


def restore_untrained_tensors(base_dir: Path, merged_dir: Path, log=print) -> None:
    """Copy base tensors that transformers does not load (Qwen3.5's `mtp.*` multi-token-prediction block) into the
    merged checkpoint. Without them llama.cpp's converter still announces the MTP layer, and loading fails with
    "tensor 'blk.24.attn_norm.weight' not found"."""
    from safetensors import safe_open
    from safetensors.torch import load_file, save_file
    merged_files = sorted(merged_dir.glob("*.safetensors"))
    merged_keys = set()
    for f in merged_files:
        with safe_open(str(f), "pt") as handle:
            merged_keys |= set(handle.keys())
    missing = {}
    for f in sorted(base_dir.glob("*.safetensors")):
        with safe_open(str(f), "pt") as handle:
            for key in handle.keys():
                if key not in merged_keys:
                    missing[key] = handle.get_tensor(key)
    if not missing:
        return
    target = merged_files[-1]
    tensors = load_file(str(target))
    dtype = next(iter(tensors.values())).dtype
    tensors.update({k: v.to(dtype) for k, v in missing.items()})
    save_file(tensors, str(target), metadata={"format": "pt"})
    index = merged_dir / "model.safetensors.index.json"
    if index.exists():
        data = json.loads(index.read_text())
        data["weight_map"].update({k: target.name for k in missing})
        index.write_text(json.dumps(data, indent=2))
    log(f"restored {len(missing)} untrained base tensors (e.g. {sorted(missing)[0]})")


def to_gguf(merged: Path, out: Path, convert_script: Path, quantize_bin: Path, log=print) -> Path:
    """HF -> GGUF f16 (llama.cpp convert_hf_to_gguf.py) -> Q4_K_M (llama-quantize)."""
    f16 = out.with_name(out.stem + "-f16.gguf")
    subprocess.run([sys.executable, str(convert_script), str(merged), "--outtype", "f16", "--outfile", str(f16)], check=True)
    subprocess.run([str(quantize_bin), str(f16), str(out), "Q4_K_M"], check=True)
    f16.unlink()
    log(f"wrote {out} ({out.stat().st_size / 1e6:.0f} MB)")
    return out


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=str(HERE.parent / "artifacts/llm/Qwen3.5-0.8B-hf"))
    parser.add_argument("--out", type=Path, default=HERE.parent / "artifacts/llm/lora-smoke")
    parser.add_argument("--smoke", action="store_true", help="CPU, 2 optimiser steps on 16 rows, then merge + save")
    parser.add_argument("--convert", type=Path, help="llama.cpp convert_hf_to_gguf.py, to also test GGUF export")
    parser.add_argument("--quantize", type=Path, help="llama-quantize binary")
    args = parser.parse_args()
    from .gen_train import generate
    rows = generate(16 if args.smoke else 3000)
    merged = train(args.model, rows, args.out, epochs=1, batch_size=2, accumulate=1, max_steps=2 if args.smoke else None)
    if args.convert and args.quantize:
        to_gguf(merged, args.out / "qwen3.5-0.8b-pandastic-Q4_K_M.gguf", args.convert, args.quantize)
