#!/usr/bin/env python3
"""生成式召回 · RQ-VAE 语义 ID 训练 → CSV(PyTorch)。

TIGER 范式的第一步:把每个 item 的向量量化成一串"由粗到细"的 codeword —— 语义 ID,
共享前缀的 item 语义相近(c0 同=粗簇,c0c1 同=更细,c0c1c2 同=最细)。

输入:同目录 item_tower.csv(由 train_two_tower.py 产出,格式 `item_id,v0..v63`,无表头)。
  这是学到的 item 行为向量;也可用 --input 指向任意 `item_id,<向量>` CSV(如内容 embedding 导出)。
输出:同目录 item_semantic_id.csv(表头 `item_id,c0,c1,c2`),由 Java 作业 import-semantic-id 灌库;
  在线 SemanticIdRecaller 按语义 ID 前缀检索同簇 item(见 docs/04 §14)。

模型 = RQ-VAE(残差量化自编码器):
  encoder(MLP) → 潜向量 z;对 z 做 L=3 层残差量化:每层在自己的 codebook(K 个向量)里取最近邻、
  减去、对残差继续下一层;量化向量 = 三层 codeword 之和;decoder(MLP) 重构原向量。
  loss = 重构 MSE + commitment(beta·‖sg(codeword)-residual‖²)+ codebook(‖codeword-sg(residual)‖²);
  前向用 straight-through 让梯度穿过量化。codeword 的 index 三元组即语义 ID。

注意:LEVELS 固定为 3(与 item_semantic_id 表的 c0/c1/c2 及在线 recaller 对齐,改了要同步)。

用法:
    pip install -r requirements-deepfm.txt   # 复用 torch
    python train_rqvae.py                    # 默认读 item_tower.csv,K=256
    python train_rqvae.py --codes 64 --epochs 50
"""
import argparse
import csv
import os
import sys

import numpy as np
import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_INPUT = os.path.join(HERE, "item_tower.csv")
OUT_CSV = os.path.join(HERE, "item_semantic_id.csv")

LEVELS = 3  # 与 item_semantic_id(c0,c1,c2) + SemanticIdRecaller 对齐,勿改


def load_vectors(path):
    ids, vecs = [], []
    with open(path, newline="") as f:
        for row in csv.reader(f):
            if not row:
                continue
            try:
                iid = int(row[0])
                v = [float(x) for x in row[1:]]
            except ValueError:
                continue  # 表头/脏行
            ids.append(iid)
            vecs.append(v)
    if not ids:
        sys.exit(f"{path} 没有可用向量;先跑 train_two_tower.py 产出 item_tower.csv")
    return np.array(ids, dtype="int64"), np.array(vecs, dtype="float32")


class RQVAE(nn.Module):
    def __init__(self, in_dim, latent_dim, n_codes, levels):
        super().__init__()
        self.levels = levels
        self.n_codes = n_codes
        self.encoder = nn.Sequential(
            nn.Linear(in_dim, 128), nn.ReLU(), nn.Linear(128, latent_dim))
        self.decoder = nn.Sequential(
            nn.Linear(latent_dim, 128), nn.ReLU(), nn.Linear(128, in_dim))
        # 每层一个 codebook [n_codes, latent_dim]
        self.codebooks = nn.ParameterList([
            nn.Parameter(torch.randn(n_codes, latent_dim) * 0.1) for _ in range(levels)])

    def quantize(self, z):
        """残差量化:返回量化向量(straight-through)、各层 index、commitment/codebook 损失。"""
        residual = z
        quantized = torch.zeros_like(z)
        indices = []
        commit_loss = 0.0
        codebook_loss = 0.0
        for lvl in range(self.levels):
            cb = self.codebooks[lvl]                          # [K, d]
            # 残差到各 codeword 的 L2 距离 → 最近邻
            d = torch.cdist(residual, cb)                     # [N, K]
            idx = torch.argmin(d, dim=1)                      # [N]
            code = cb[idx]                                    # [N, d]
            commit_loss = commit_loss + ((code.detach() - residual) ** 2).mean()
            codebook_loss = codebook_loss + ((code - residual.detach()) ** 2).mean()
            quantized = quantized + code
            residual = residual - code
            indices.append(idx)
        # straight-through:前向用 quantized,反向梯度直通到 z
        quantized_st = z + (quantized - z).detach()
        return quantized_st, torch.stack(indices, dim=1), commit_loss, codebook_loss

    def forward(self, x):
        z = self.encoder(x)
        q, idx, commit, cb = self.quantize(z)
        recon = self.decoder(q)
        return recon, idx, commit, cb


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=DEFAULT_INPUT)
    ap.add_argument("--codes", type=int, default=256, help="每层 codebook 大小 K")
    ap.add_argument("--latent", type=int, default=32)
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=1024)
    ap.add_argument("--beta", type=float, default=0.25, help="commitment 权重")
    args = ap.parse_args()

    ids, vecs = load_vectors(args.input)
    n, in_dim = vecs.shape
    print(f"读入 {n} 个 item 向量,维度 {in_dim};RQ-VAE K={args.codes} L={LEVELS} latent={args.latent}")

    torch.manual_seed(42)
    model = RQVAE(in_dim, args.latent, args.codes, LEVELS)
    x = torch.from_numpy(vecs)
    # codebook 用数据潜向量初始化,缓解死码
    with torch.no_grad():
        z0 = model.encoder(x)
        for lvl in range(LEVELS):
            sel = torch.randint(0, n, (args.codes,))
            model.codebooks[lvl].copy_(z0[sel] + torch.randn(args.codes, args.latent) * 0.01)

    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    for epoch in range(1, args.epochs + 1):
        model.train()
        perm = torch.randperm(n)
        total, rec_t = 0.0, 0.0
        for i in range(0, n, args.batch):
            idx = perm[i:i + args.batch]
            opt.zero_grad()
            recon, _, commit, cb = model(x[idx])
            rec = ((recon - x[idx]) ** 2).mean()
            loss = rec + cb + args.beta * commit
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
            rec_t += rec.item() * len(idx)
        if epoch == 1 or epoch % 5 == 0 or epoch == args.epochs:
            print(f"epoch {epoch:3d} | loss {total / n:.5f} | recon {rec_t / n:.5f}")

    # 全量推语义 ID
    model.eval()
    with torch.no_grad():
        _, codes, _, _ = model(x)
    codes = codes.numpy()
    uniq = len({tuple(c) for c in codes})
    used_c0 = len(set(codes[:, 0].tolist()))
    print(f"语义 ID:唯一三元组 {uniq}/{n}(碰撞 {n - uniq}),c0 用了 {used_c0}/{args.codes} 个粗簇")

    with open(OUT_CSV, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["item_id", "c0", "c1", "c2"])
        for iid, c in zip(ids.tolist(), codes.tolist()):
            w.writerow([iid, c[0], c[1], c[2]])
    print(f"\n✅ 导出 {n} 行 → {OUT_CSV}\n  下一步:--job=import-semantic-id 灌库,在线 GENERATIVE 召回即可用")


if __name__ == "__main__":
    main()
