#!/usr/bin/env python3
"""R9 MIND 多兴趣召回训练：时序正反馈 → 动态路由 K 兴趣 → item sampled-softmax。

输入 samples_mt.csv（gen-samples-mt 的 point-in-time 序列）；输出：
  * recsys-recall/.../model/mind_user.onnx: history_items[N,H] -> interests[N,K,64]
  * mind_schema.json + mind_item_vocab.csv（在线强契约）
  * train/mind_item_embedding.csv: model_version,item_id,v0..v63（import-r9-embeddings）
"""
import argparse
import csv
import json
import os
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
MODEL_DIR = os.path.join(ROOT, "recsys-recall", "src", "main", "resources", "model")
DIM = 64


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--samples", default=os.path.join(HERE, "samples_mt.csv"))
    p.add_argument("--epochs", type=int, default=8)
    p.add_argument("--max-history", type=int, default=50)
    p.add_argument("--num-interests", type=int, default=4)
    p.add_argument("--batch-size", type=int, default=512)
    p.add_argument("--max-rows", type=int, default=0)
    p.add_argument("--model-version", default="r9-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"))
    return p.parse_args()


def parse_seq(value):
    if not isinstance(value, str) or not value.strip():
        return []
    return [int(x) for x in value.split("|") if x.strip()]


class Mind(nn.Module):
    def __init__(self, item_count, interests, routing_iters=3):
        super().__init__()
        self.item = nn.Embedding(item_count + 1, DIM, padding_idx=0)
        self.routing_query = nn.Parameter(torch.empty(interests, DIM))
        self.interests = interests
        self.routing_iters = routing_iters
        nn.init.normal_(self.item.weight, std=0.05)
        nn.init.orthogonal_(self.routing_query)
        with torch.no_grad():
            self.item.weight[0].zero_()

    @staticmethod
    def squash(x):
        norm2 = (x * x).sum(dim=-1, keepdim=True)
        return norm2 / (1.0 + norm2) * x / torch.sqrt(norm2 + 1e-9)

    def encode(self, history):
        behavior = self.item(history)                         # [B,H,D]
        mask = history.ne(0).unsqueeze(-1)                    # [B,H,1]
        # 学习型 capsule prior 打破 K 路完全对称；随后仍用 MIND dynamic routing 迭代聚类。
        logits = torch.einsum("bhd,kd->bhk", behavior, self.routing_query)
        capsules = torch.zeros(history.size(0), self.interests, DIM,
                               device=history.device, dtype=behavior.dtype)
        for step in range(self.routing_iters):
            weight = torch.softmax(logits, dim=2) * mask
            routed = self.squash(torch.einsum("bhk,bhd->bkd", weight, behavior))
            # slot residual 保持 capsule 身份，防止所有兴趣在热门方向塌成同一向量。
            capsules = F.normalize(routed + 0.3 * F.normalize(self.routing_query, dim=-1).unsqueeze(0), dim=-1)
            if step + 1 < self.routing_iters:
                logits = logits + torch.einsum("bhd,bkd->bhk", behavior, capsules)
        return F.normalize(capsules, dim=-1)

    def forward(self, history):
        return self.encode(history)


def main():
    args = parse_args()
    if not os.path.exists(args.samples):
        sys.exit(f"找不到 {args.samples};请先跑 --job=gen-samples-mt")
    cols = ["label_like", "item_id", "seq_items", "split"]
    df = pd.read_csv(args.samples, usecols=cols)
    df = df[(df.label_like == 1) & (df.split == "train")].copy()
    df["seq"] = df.seq_items.map(parse_seq)
    df = df[df.seq.map(bool)]
    if args.max_rows > 0 and len(df) > args.max_rows:
        df = df.sample(args.max_rows, random_state=42)
    if len(df) < 10:
        sys.exit("MIND 有效正样本不足 10")

    item_ids = set(df.item_id.astype("int64").tolist())
    for seq in df.seq: item_ids.update(seq)
    item_ids = sorted(item_ids)
    vocab = {item_id: i + 1 for i, item_id in enumerate(item_ids)}
    history = np.zeros((len(df), args.max_history), dtype="int64")
    for row, seq in enumerate(df.seq):
        encoded = [vocab[x] for x in seq if x in vocab][-args.max_history:]
        history[row, -len(encoded):] = encoded
    target = df.item_id.astype("int64").map(vocab).to_numpy(dtype="int64", copy=True)

    torch.manual_seed(42)
    model = Mind(len(vocab), args.num_interests)
    h = torch.from_numpy(history)
    y = torch.from_numpy(target)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-6)
    for epoch in range(1, args.epochs + 1):
        perm = torch.randperm(len(h)); total = 0.0; seen = 0
        for start in range(0, len(h), args.batch_size):
            idx = perm[start:start + args.batch_size]
            if len(idx) < 2: continue
            interests = model.encode(h[idx])
            target_vec = F.normalize(model.item(y[idx]), dim=-1)
            # 每个 user-target 对取最匹配兴趣；batch 内其他 target 是 sampled negatives。
            logits = torch.einsum("bkd,nd->bkn", interests, target_vec).max(dim=1).values / 0.07
            gram = torch.einsum("bkd,bjd->bkj", interests, interests)
            eye = torch.eye(args.num_interests).unsqueeze(0)
            diversity = ((gram - eye) ** 2).mean()
            query = F.normalize(model.routing_query, dim=-1)
            query_gram = query @ query.t()
            query_diversity = ((query_gram - torch.eye(args.num_interests)) ** 2).mean()
            loss = F.cross_entropy(logits, torch.arange(len(idx))) + 0.3 * diversity + 0.3 * query_diversity
            opt.zero_grad(); loss.backward(); opt.step()
            total += loss.item() * len(idx); seen += len(idx)
        print(f"epoch {epoch:02d} loss={total/max(1,seen):.5f}")

    model.eval()
    os.makedirs(MODEL_DIR, exist_ok=True)
    model_path = os.path.join(MODEL_DIR, "mind_user.onnx")
    schema_path = os.path.join(MODEL_DIR, "mind_schema.json")
    vocab_path = os.path.join(MODEL_DIR, "mind_item_vocab.csv")
    item_path = os.path.join(HERE, "mind_item_embedding.csv")
    dummy = torch.zeros(2, args.max_history, dtype=torch.int64)
    dummy[:, -1] = 1
    torch.onnx.export(model, (dummy,), model_path, input_names=["history_items"],
                      output_names=["interests"],
                      dynamic_axes={"history_items": {0: "N"}, "interests": {0: "N"}},
                      opset_version=17, dynamo=False)
    import onnx
    graph = onnx.load(model_path); graph.ir_version = 9
    onnx.save_model(graph, model_path, save_as_external_data=False)
    data_file = model_path + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)

    with open(vocab_path, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["item_id", "item_idx"])
        w.writerows((item_id, vocab[item_id]) for item_id in item_ids)
    with torch.no_grad():
        vectors = F.normalize(model.item.weight[1:], dim=-1).numpy()
    with open(item_path, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["model_version", "item_id"] + [f"v{i}" for i in range(DIM)])
        for item_id, vector in zip(item_ids, vectors):
            w.writerow([args.model_version, item_id] + [f"{x:.8f}" for x in vector])
    with open(schema_path, "w") as f:
        json.dump({"contract_version": 1, "algorithm": "mind", "model_version": args.model_version,
                   "dim": DIM, "num_interests": args.num_interests, "max_history": args.max_history,
                   "pad_index": 0, "input_name": "history_items", "output_name": "interests"},
                  f, ensure_ascii=False, indent=2)

    import onnxruntime as ort
    session = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    probe = session.run(["interests"], {"history_items": history[:8]})[0]
    if probe.shape != (min(8, len(history)), args.num_interests, DIM) or not np.isfinite(probe).all():
        sys.exit("MIND ONNX 回读 shape/finite 校验失败")
    gram = np.einsum("bkd,bjd->bkj", probe, probe)
    off_diag = gram[:, ~np.eye(args.num_interests, dtype=bool)].mean()
    if off_diag > 0.98:
        sys.exit(f"MIND 兴趣塌缩自检失败:平均 cosine={off_diag:.4f}")
    print(f"MIND ONNX 回读 OK shape={probe.shape},兴趣间平均 cosine={off_diag:.4f}")
    print(f"version={args.model_version};vocab={len(vocab)};samples={len(df)}")


if __name__ == "__main__": main()
