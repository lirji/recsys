#!/usr/bin/env python3
"""R9 LightGCN 图召回训练：user-item 正反馈二部图 + BPR，输出版本化 64 维 user/item 向量。"""
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


def args():
    p = argparse.ArgumentParser()
    p.add_argument("--samples", default=os.path.join(HERE, "samples_mt.csv"))
    p.add_argument("--epochs", type=int, default=20)
    p.add_argument("--layers", type=int, default=2)
    p.add_argument("--max-edges", type=int, default=0)
    p.add_argument("--model-version", default="r9-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S"))
    return p.parse_args()


class LightGCN(nn.Module):
    def __init__(self, users, items, adjacency, layers):
        super().__init__(); self.users = users; self.items = items; self.adj = adjacency; self.layers = layers
        self.embedding = nn.Embedding(users + items, DIM)
        nn.init.normal_(self.embedding.weight, std=0.05)

    def propagated(self):
        current = self.embedding.weight; all_layers = [current]
        for _ in range(self.layers):
            current = torch.sparse.mm(self.adj, current); all_layers.append(current)
        final = torch.stack(all_layers, dim=0).mean(dim=0)
        return final[:self.users], final[self.users:]


def main():
    a = args()
    if not os.path.exists(a.samples): sys.exit(f"找不到 {a.samples}")
    df = pd.read_csv(a.samples, usecols=["label_like", "user_id", "item_id", "split"])
    df = df[(df.label_like == 1) & (df.split == "train")][["user_id", "item_id"]].drop_duplicates()
    if a.max_edges > 0 and len(df) > a.max_edges: df = df.sample(a.max_edges, random_state=42)
    if len(df) < 10: sys.exit("LightGCN 正边不足 10")
    user_ids = sorted(df.user_id.astype("int64").unique()); item_ids = sorted(df.item_id.astype("int64").unique())
    u2i = {x:i for i,x in enumerate(user_ids)}; p2i = {x:i for i,x in enumerate(item_ids)}
    edge_u = df.user_id.map(u2i).to_numpy(dtype="int64"); edge_i = df.item_id.map(p2i).to_numpy(dtype="int64")
    nu, ni = len(user_ids), len(item_ids)
    deg_u = np.bincount(edge_u, minlength=nu); deg_i = np.bincount(edge_i, minlength=ni)
    weights = 1.0 / np.sqrt(deg_u[edge_u] * deg_i[edge_i])
    rows = np.concatenate([edge_u, nu + edge_i]); cols = np.concatenate([nu + edge_i, edge_u])
    vals = np.concatenate([weights, weights]).astype("float32")
    adjacency = torch.sparse_coo_tensor(torch.tensor([rows, cols]), torch.from_numpy(vals),
                                        (nu + ni, nu + ni)).coalesce()
    positives = {u:set() for u in range(nu)}
    for u,i in zip(edge_u,edge_i): positives[u].add(i)
    rng = np.random.default_rng(42); torch.manual_seed(42)
    model = LightGCN(nu, ni, adjacency, a.layers); opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    edge_u_t = torch.from_numpy(edge_u); edge_i_t = torch.from_numpy(edge_i)
    for epoch in range(1, a.epochs + 1):
        neg = rng.integers(0, ni, size=len(edge_i), dtype="int64")
        bad = np.array([n in positives[int(u)] for u,n in zip(edge_u,neg)])
        while bad.any():
            neg[bad] = rng.integers(0, ni, size=int(bad.sum()))
            bad = np.array([n in positives[int(u)] for u,n in zip(edge_u,neg)])
        users, items = model.propagated(); neg_t = torch.from_numpy(neg)
        pos_score = (users[edge_u_t] * items[edge_i_t]).sum(1)
        neg_score = (users[edge_u_t] * items[neg_t]).sum(1)
        loss = F.softplus(neg_score - pos_score).mean() + 1e-5 * model.embedding.weight.square().mean()
        opt.zero_grad(); loss.backward(); opt.step()
        if epoch == 1 or epoch % 5 == 0 or epoch == a.epochs:
            margin = (pos_score - neg_score).mean().item()
            print(f"epoch {epoch:02d} bpr={loss.item():.5f} margin={margin:.5f}")

    model.eval()
    with torch.no_grad(): users, items = model.propagated(); users = F.normalize(users,dim=1).numpy(); items = F.normalize(items,dim=1).numpy()
    user_path = os.path.join(HERE, "graph_user_embedding.csv"); item_path = os.path.join(HERE, "graph_item_embedding.csv")
    write(user_path, a.model_version, user_ids, users); write(item_path, a.model_version, item_ids, items)
    os.makedirs(MODEL_DIR, exist_ok=True)
    schema_path = os.path.join(MODEL_DIR, "lightgcn_schema.json")
    with open(schema_path, "w") as f:
        json.dump({"contract_version":1,"algorithm":"lightgcn","model_version":a.model_version,
                   "dim":DIM,"layers":a.layers,"users":nu,"items":ni,"edges":len(df)}, f, indent=2)
    # 对抗性自检：用新负样本验证正边平均得分应更高。
    probe = min(5000, len(edge_u)); idx = rng.choice(len(edge_u), probe, replace=False)
    neg = rng.integers(0, ni, probe)
    pos_mean = np.sum(users[edge_u[idx]] * items[edge_i[idx]], axis=1).mean()
    neg_mean = np.sum(users[edge_u[idx]] * items[neg], axis=1).mean()
    if not np.isfinite(users).all() or not np.isfinite(items).all() or pos_mean <= neg_mean:
        sys.exit(f"LightGCN 自检失败 pos={pos_mean} neg={neg_mean}")
    print(f"LightGCN 自检 OK pos={pos_mean:.5f}>neg={neg_mean:.5f};version={a.model_version};users={nu};items={ni}")


def write(path, version, ids, vectors):
    with open(path,"w",newline="") as f:
        w=csv.writer(f); w.writerow(["model_version","id"]+[f"v{i}" for i in range(DIM)])
        for identifier,vector in zip(ids,vectors): w.writerow([version,identifier]+[f"{x:.8f}" for x in vector])


if __name__ == "__main__": main()
