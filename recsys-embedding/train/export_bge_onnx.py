#!/usr/bin/env python3
"""把 BGE 句向量模型导出为单个自包含 ONNX,供 Java(onnxruntime)离线向量化。

产物(默认 ~/.recsys/models/bge-base-en-v1.5):
  - model.onnx   编码器,输入 input_ids/attention_mask/token_type_ids,输出 last_hidden_state[B,L,H]
  - vocab.txt    BERT WordPiece 词表(行号即 token id),供纯 Java BgeTokenizer 读取

池化(取 [CLS])+ L2 归一化在 Java 侧完成(LocalBgeEmbeddingClient),与训练侧 sentence-transformers
口径一致;这里只导出 transformer 编码器,保持模型小而通用。

为什么是 bge-base-en-v1.5:输出 768 维,正好对齐全库 item_embedding vector(768) 契约,
无需改 schema;MovieLens 文本为英文。若改用 bge-small(384 维)需同时改 schema 与
recsys.embedding.dimension。

用法:
  pip install -r requirements-bge.txt
  python export_bge_onnx.py                       # 默认 bge-base-en-v1.5 → ~/.recsys/models/...
  python export_bge_onnx.py --model BAAI/bge-base-en-v1.5 --out /path/to/dir --seq-len 256
  python export_bge_onnx.py --verify              # 导出后用 onnxruntime 复算并与 torch 对比余弦

导出完毕后:
  在跑向量化的服务上设 EMBEDDING_PROVIDER=local(若模型不在默认目录,另设
  BGE_MODEL_PATH / BGE_VOCAB_PATH),然后重跑 backfill-embedding 灌满全量向量。
"""
import argparse
import os
from pathlib import Path

import torch
from transformers import AutoModel, AutoTokenizer


class Encoder(torch.nn.Module):
    """只暴露 last_hidden_state,三输入显式命名,便于 Java 按名喂入。"""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask, token_type_ids):
        out = self.model(
            input_ids=input_ids,
            attention_mask=attention_mask,
            token_type_ids=token_type_ids,
        )
        return out.last_hidden_state


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="BAAI/bge-base-en-v1.5")
    ap.add_argument(
        "--out",
        default=str(Path.home() / ".recsys" / "models" / "bge-base-en-v1.5"),
    )
    ap.add_argument("--seq-len", type=int, default=256)
    ap.add_argument("--opset", type=int, default=18)
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    onnx_path = out / "model.onnx"

    print(f"加载 {args.model} ...")
    tok = AutoTokenizer.from_pretrained(args.model)
    model = AutoModel.from_pretrained(args.model)
    model.eval()
    hidden = model.config.hidden_size
    print(f"hidden_size = {hidden}(应 == recsys.embedding.dimension)")

    enc = tok(
        "hello world",
        return_tensors="pt",
        padding="max_length",
        truncation=True,
        max_length=args.seq_len,
    )
    inputs = (enc["input_ids"], enc["attention_mask"], enc["token_type_ids"])
    dyn = {0: "batch", 1: "seq"}

    print(f"导出 ONNX → {onnx_path}")
    with torch.no_grad():
        torch.onnx.export(
            Encoder(model),
            inputs,
            str(onnx_path),
            input_names=["input_ids", "attention_mask", "token_type_ids"],
            output_names=["last_hidden_state"],
            dynamic_axes={
                "input_ids": dyn,
                "attention_mask": dyn,
                "token_type_ids": dyn,
                "last_hidden_state": {0: "batch", 1: "seq"},
            },
            opset_version=args.opset,
            do_constant_folding=True,
            # 强制 legacy(TorchScript)导出器:单个自包含 .onnx(无外部 .data),
            # 因为 Java 侧用 createSession(byte[]) 加载,解析不到外部权重文件。
            # 也才能正确生成 batch/seq 动态维(dynamo 路径会把它们固化)。
            dynamo=False,
        )

    # vocab.txt(行号即 token id),供 Java BgeTokenizer 读取。
    # transformers 5.x 的 fast 分词器 save_pretrained 只写 tokenizer.json,故直接从
    # get_vocab() 按 id 升序还原 vocab.txt(WordPiece 的 token↔id 映射即真相)。
    vocab_map = tok.get_vocab()  # token -> id
    id_to_tok = {i: t for t, i in vocab_map.items()}
    n = max(id_to_tok) + 1
    vocab = out / "vocab.txt"
    with open(vocab, "w", encoding="utf-8") as f:
        for i in range(n):
            f.write(id_to_tok.get(i, "[UNK]") + "\n")
    print(f"词表 → {vocab}({n} tokens)")

    size_mb = os.path.getsize(onnx_path) / 1e6
    print(f"完成。model.onnx ≈ {size_mb:.0f} MB")

    if args.verify:
        _verify(args.model, tok, model, onnx_path, args.seq_len)

    print(
        "\n下一步:\n"
        "  export EMBEDDING_PROVIDER=local\n"
        f"  # 若非默认目录:export BGE_MODEL_PATH={onnx_path}  BGE_VOCAB_PATH={vocab}\n"
        "  mvn -pl recsys-offline spring-boot:run \\\n"
        "    -Dspring-boot.run.arguments=\"--job=backfill-embedding --skip-existing\""
    )


def _verify(model_name, tok, model, onnx_path, seq_len):
    import numpy as np
    import onnxruntime as ort

    # 逐条(batch=1,与在线 Java 路径一致)对比,避免批内 padding 引入的数值差。
    texts = ["Toy Story (1995) Animation Children Comedy", "The Matrix (1999) Action Sci-Fi"]
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    cos = []
    for t in texts:
        enc = tok(t, return_tensors="pt", truncation=True, max_length=seq_len)
        with torch.no_grad():
            ref = model(**enc).last_hidden_state[:, 0][0].numpy()
        ref = ref / np.linalg.norm(ref)
        feeds = {k: enc[k].numpy() for k in ("input_ids", "attention_mask", "token_type_ids")}
        got = sess.run(["last_hidden_state"], feeds)[0][:, 0][0]
        got = got / np.linalg.norm(got)
        cos.append(round(float((ref * got).sum()), 6))
    print(f"[verify] torch vs onnx 逐条余弦 = {cos}(应 ≈ 1.0)")


if __name__ == "__main__":
    main()
