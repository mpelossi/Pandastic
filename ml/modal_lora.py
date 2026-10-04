"""Modal app: LoRA fine-tune Qwen3.5-0.8B for SMS slots, export GGUF Q4_K_M, evaluate.

Run from ml/ (needs `modal token new` once):
  modal run modal_lora.py                  # ~3,000 synthetic SMS, 2 epochs on an L4, merge, GGUF, held-out eval
  modal run modal_lora.py --smoke          # 5 steps: checks the whole GPU path cheaply first
Then:
  modal volume get pandastic-models lora/<version> ./artifacts/
The GGUF replaces the base model on the phone (same file name pattern and size, ~530 MB); keep the base GGUF
if the held-out score in eval.json is not better than ml/reports/nlu_eval.json.
"""
import json
from pathlib import Path

import modal

LLAMA_TAG = "b11381"  # same llama.cpp build as the app's JNI and ml/llm/eval_llm.py
app = modal.App("pandastic-lora")
image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("git", "curl", "libgomp1")
    .pip_install("torch==2.14.1", "transformers==5.18.0", "peft==0.21.2", "accelerate", "safetensors", "sentencepiece",
                 "protobuf", "numpy", "requests", "huggingface_hub")
    .run_commands(
        f"git clone --depth 1 --branch {LLAMA_TAG} https://github.com/ggml-org/llama.cpp /opt/llama.cpp",
        "pip install /opt/llama.cpp/gguf-py",
        f"curl -sL https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA_TAG}/llama-{LLAMA_TAG}-bin-ubuntu-x64.tar.gz"
        " | tar -xz -C /opt && ln -s /opt/llama-* /opt/llama-bin || true",
    )
    # The llm package needs its prompt, grammar and eval CSVs, not only .py files.
    .add_local_python_source("llm", "leaf", ignore=["**/__pycache__", "**/*.pyc"])
)
models_volume = modal.Volume.from_name("pandastic-models", create_if_missing=True)
hf_cache = modal.Volume.from_name("pandastic-hf-cache", create_if_missing=True)


@app.function(image=image, gpu="A100-80GB", cpu=8, memory=32768, timeout=3 * 3600,
              env={"PYTORCH_CUDA_ALLOC_CONF": "expandable_segments:True"},
              volumes={"/models": models_volume, "/root/.cache/huggingface": hf_cache})
def finetune(n_rows: int, epochs: int, smoke: bool) -> dict:
    import glob
    from datetime import datetime, timezone
    from huggingface_hub import snapshot_download
    from llm import eval_llm, gen_train, train_lora

    version = "lora-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M") + ("-smoke" if smoke else "")
    out = Path("/models/lora") / version
    base = snapshot_download("Qwen/Qwen3.5-0.8B")
    rows = gen_train.generate(64 if smoke else n_rows)
    merged = train_lora.train(base, rows, out, epochs=1 if smoke else epochs, max_steps=5 if smoke else None)
    bin_dir = Path(sorted(glob.glob("/opt/llama-*/"))[0])
    quantize = next(bin_dir.rglob("llama-quantize"))
    server = next(bin_dir.rglob("llama-server"))
    gguf = train_lora.to_gguf(merged, out / "Qwen3.5-0.8B-pandastic-Q4_K_M.gguf",
                              Path("/opt/llama.cpp/convert_hf_to_gguf.py"), quantize)
    import shutil
    shutil.rmtree(merged)  # keep the adapter + GGUF only
    # Predictions are saved next to the GGUF, so the keyword + LLM hybrid can be scored later without the model.
    results = eval_llm.evaluate_model(str(server), str(gguf), threads=8, predictions_dir=out)
    (out / "eval.json").write_text(json.dumps(results, indent=2))
    models_volume.commit()
    return {"version": version, "gguf": str(gguf), "heldout_all_slots": results["heldout"]["qwen"]["all_slots"],
            "dev_all_slots": results["dev"]["qwen"]["all_slots"]}


@app.local_entrypoint()
def main(n_rows: int = 3000, epochs: int = 2, smoke: bool = False):
    result = finetune.remote(n_rows, epochs, smoke)
    print(json.dumps(result, indent=2))
    print(f"\nmodal volume get pandastic-models lora/{result['version']} ./artifacts/")
