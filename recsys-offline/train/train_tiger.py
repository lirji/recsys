#!/usr/bin/env python3
"""生成式推荐 · TIGER 自回归生成训练 → ONNX(PyTorch)。

完整 TIGER(Transformer Index for GEnerative Recommenders)第二步:在 RQ-VAE 语义 ID 之上,
训练一个 **decoder-only Transformer**,把用户历史 item 的语义 ID token 序列作为前缀,
**自回归生成下一个 item 的语义 ID**(c0→c1→c2)。在线再 beam search 解码 + 映射回 item。

输入:
  - item_semantic_id.csv(train_rqvae.py 产出,`item_id,c0,c1,c2`):item → 语义 ID;
  - samples_mt.csv(gen-samples-mt 产出):取 label_like=1 且历史非空的正样本,
    seq_items 作历史、item_id 作"下一个 item"目标 → (历史, 目标) 对。

token 化(在线/离线契约,Java TigerRecaller 必须一致):
  PAD=0, BOS=1;码 token = 2 + level*K + code(level∈{0,1,2},code∈[0,K))。
  序列 = [历史各 item 的 3 个码 token(oldest→newest,截断最近 H 个)] + BOS + [t0,t1,t2]。
  训练:teacher forcing,causal mask,只在 BOS/t0/t1 三个位置对 t0/t1/t2 算交叉熵。
  推理:causal=False(只喂前缀,末位 attend 全前缀=等价 causal 末位),逐级 beam search。

导出 tiger.onnx(输入 tokens[N,T] int64 → logits[N,T,V] float),放入 **recsys-recall** 资源。
与其它模型相同的 IR9 / 单自包含 .onnx 注意事项。重训后 `mvn -pl recsys-recall clean install` 重打包。

用法:
    pip install -r requirements-deepfm.txt
    python train_rqvae.py && (灌库) && python train_tiger.py
"""
import json
import os
import sys

import numpy as np
import pandas as pd
import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
SEMID = os.path.join(HERE, "item_semantic_id.csv")
SAMPLES = os.path.join(HERE, "samples_mt.csv")
MODEL_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "recsys-recall", "src", "main", "resources", "model"))
MODEL_PATH = os.path.join(MODEL_DIR, "tiger.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "tiger_schema.json")

LEVELS = 3
MAX_HIST = 20                 # 最多取最近 H 个历史 item
PAD, BOS = 0, 1
D_MODEL, NHEAD, NLAYERS, FF = 64, 4, 2, 128


def load_semids():
    df = pd.read_csv(SEMID)
    m = {}
    kmax = 0
    for _, r in df.iterrows():
        c = (int(r["c0"]), int(r["c1"]), int(r["c2"]))
        m[int(r["item_id"])] = c
        kmax = max(kmax, c[0], c[1], c[2])
    return m, kmax + 1


def code_token(level, code, k):
    return 2 + level * k + code


def build_dataset(semid, k):
    df = pd.read_csv(SAMPLES)
    need = ["label_like", "item_id", "seq_items"]
    if any(c not in df.columns for c in need):
        sys.exit(f"samples_mt.csv 缺列 {need};先跑 gen-samples-mt")
    pos = df[df["label_like"] == 1]
    max_len = MAX_HIST * LEVELS + 1 + LEVELS
    seqs, tgts, bos_idx = [], [], []
    for _, r in pos.iterrows():
        tgt = semid.get(int(r["item_id"]))
        if tgt is None:
            continue
        raw = str(r["seq_items"]) if not pd.isna(r["seq_items"]) else ""
        hist_items = [int(x) for x in raw.split("|") if x != ""]
        if not hist_items:
            continue
        hist_items = hist_items[-MAX_HIST:]
        toks = []
        for iid in hist_items:
            c = semid.get(iid)
            if c is None:
                continue
            for lvl in range(LEVELS):
                toks.append(code_token(lvl, c[lvl], k))
        if not toks:
            continue
        bi = len(toks)                       # BOS 位置
        toks.append(BOS)
        for lvl in range(LEVELS):
            toks.append(code_token(lvl, tgt[lvl], k))
        # 右 pad
        toks = toks[:max_len] + [PAD] * (max_len - len(toks))
        seqs.append(toks)
        tgts.append([code_token(0, tgt[0], k), code_token(1, tgt[1], k), code_token(2, tgt[2], k)])
        bos_idx.append(bi)
    return (np.array(seqs, dtype="int64"), np.array(tgts, dtype="int64"),
            np.array(bos_idx, dtype="int64"), max_len)


class Block(nn.Module):
    """单层 pre-LN Transformer。手写多头自注意力(reshape 用动态 shape,避免 nn.MultiheadAttention
    导出 ONNX 时烘焙固定 batch/seq —— 这是 TIGER 在线变长 beam 解码能跑通的关键)。"""

    def __init__(self):
        super().__init__()
        self.h = NHEAD
        self.dh = D_MODEL // NHEAD
        self.ln1 = nn.LayerNorm(D_MODEL)
        self.ln2 = nn.LayerNorm(D_MODEL)
        self.qkv = nn.Linear(D_MODEL, 3 * D_MODEL)
        self.proj = nn.Linear(D_MODEL, D_MODEL)
        self.ff = nn.Sequential(nn.Linear(D_MODEL, FF), nn.GELU(), nn.Linear(FF, D_MODEL))

    def forward(self, x, attn_bias):
        b = x.size(0)
        t = x.size(1)
        y = self.ln1(x)
        qkv = self.qkv(y).reshape(b, t, 3, self.h, self.dh).permute(2, 0, 3, 1, 4)  # [3,b,h,t,dh]
        q, k, v = qkv[0], qkv[1], qkv[2]                                            # [b,h,t,dh]
        scores = (q @ k.transpose(-2, -1)) / (self.dh ** 0.5)                       # [b,h,t,t]
        if attn_bias is not None:
            scores = scores + attn_bias                                             # [b,1,t,t] 或 [1,1,t,t]
        att = torch.softmax(scores, dim=-1)
        o = (att @ v).permute(0, 2, 1, 3).reshape(b, t, D_MODEL)                     # [b,t,d]
        x = x + self.proj(o)
        x = x + self.ff(self.ln2(x))
        return x


class Tiger(nn.Module):
    def __init__(self, vocab, max_len):
        super().__init__()
        self.tok = nn.Embedding(vocab, D_MODEL, padding_idx=PAD)
        self.pos = nn.Embedding(max_len, D_MODEL)
        self.blocks = nn.ModuleList([Block() for _ in range(NLAYERS)])
        self.lnf = nn.LayerNorm(D_MODEL)
        self.head = nn.Linear(D_MODEL, vocab)
        self.max_len = max_len

    def forward(self, tokens, causal: bool = False):
        t = tokens.size(1)
        pos_ids = torch.arange(t, device=tokens.device).unsqueeze(0)
        x = self.tok(tokens) + self.pos(pos_ids)
        # padding 偏置:padded key 置 -inf(训练时序列右 pad;推理只喂前缀无 pad,等价无偏置)
        bias = torch.zeros(tokens.size(0), 1, 1, t, device=tokens.device)
        bias = bias.masked_fill((tokens == PAD).unsqueeze(1).unsqueeze(2), float("-inf"))
        if causal:  # 训练加 causal;导出 causal=False 不入图(推理只喂前缀,末位 attend 全前缀=等价 causal 末位)
            cm = torch.triu(torch.full((t, t), float("-inf"), device=tokens.device), diagonal=1)
            bias = bias + cm.unsqueeze(0).unsqueeze(0)
        for blk in self.blocks:
            x = blk(x, bias)
        return self.head(self.lnf(x))


def main():
    if not os.path.exists(SEMID):
        sys.exit(f"找不到 {SEMID};先跑 train_rqvae.py")
    semid, k = load_semids()
    vocab = 2 + LEVELS * k
    print(f"语义 ID:{len(semid)} 个 item,K={k},vocab={vocab}")

    seqs, tgts, bos_idx, max_len = build_dataset(semid, k)
    if len(seqs) < 100:
        sys.exit(f"训练对太少({len(seqs)});先跑 gen-samples-mt 生成更多样本")
    print(f"训练对(历史→下一item)= {len(seqs)},max_len={max_len}")

    rng = np.random.default_rng(42)
    is_tr = rng.random(len(seqs)) < 0.9
    torch.manual_seed(42)
    model = Tiger(vocab, max_len)
    X = torch.from_numpy(seqs)
    T = torch.from_numpy(tgts)
    B = torch.from_numpy(bos_idx)
    tr = np.where(is_tr)[0]
    va = np.where(~is_tr)[0]

    opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    ce = nn.CrossEntropyLoss()
    batch = 512
    best, best_state, bad = 1e9, None, 0

    def step_loss(idx):
        x = X[idx]
        logits = model(x, causal=True)              # [n,T,V]
        bi = B[idx]
        n = len(idx)
        ar = torch.arange(n)
        loss = 0.0
        for j in range(LEVELS):                     # BOS+j 位预测 t_j
            lg = logits[ar, bi + j]                 # [n,V]
            loss = loss + ce(lg, T[idx][:, j])
        return loss / LEVELS

    for epoch in range(1, 16):
        model.train()
        perm = tr[rng.permutation(len(tr))]
        tot = 0.0
        for i in range(0, len(perm), batch):
            idx = torch.from_numpy(perm[i:i + batch])
            opt.zero_grad()
            loss = step_loss(idx)
            loss.backward()
            opt.step()
            tot += loss.item() * len(idx)
        model.eval()
        with torch.no_grad():
            vidx = torch.from_numpy(va)
            vloss = step_loss(vidx).item()
            # 末级(t2)top-1 命中率粗看
            x = X[vidx]
            acc = 0
            for j in range(LEVELS):
                lg = model(x, causal=True)[torch.arange(len(vidx)), B[vidx] + j]
                acc += (lg.argmax(1) == T[vidx][:, j]).float().mean().item()
            acc /= LEVELS
        print(f"epoch {epoch:2d} | train loss {tot/len(tr):.4f} | valid loss {vloss:.4f} | tokenAcc {acc:.4f}")
        if vloss < best:
            best, bad, best_state = vloss, 0, {kk: v.clone() for kk, v in model.state_dict().items()}
        else:
            bad += 1
            if bad >= 3:
                print("早停")
                break

    if best_state:
        model.load_state_dict(best_state)
    export_onnx(model, k, vocab, max_len)
    print(f"\n✅ 导出完成:\n  {MODEL_PATH}\n  {SCHEMA_PATH}")


def export_onnx(model, k, vocab, max_len):
    os.makedirs(MODEL_DIR, exist_ok=True)
    model.eval()
    dummy = torch.tensor([[code_token(0, 0, k), code_token(1, 0, k), code_token(2, 0, k), BOS]], dtype=torch.int64)
    torch.onnx.export(
        model, (dummy,), MODEL_PATH,
        input_names=["tokens"], output_names=["logits"],
        dynamic_axes={"tokens": {0: "N", 1: "T"}, "logits": {0: "N", 1: "T"}},
        opset_version=17,
        dynamo=False,  # 关键:legacy tracer 才真正尊重 dynamic_axes(动态 N/T);dynamo=True 会烘焙固定形状
    )
    import onnx
    m = onnx.load(MODEL_PATH)
    m.ir_version = 9
    onnx.save_model(m, MODEL_PATH, save_as_external_data=False)
    data_file = MODEL_PATH + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)
    with open(SCHEMA_PATH, "w") as f:
        json.dump({
            "model": "tiger", "levels": LEVELS, "codes": k, "vocab": vocab,
            "max_hist": MAX_HIST, "max_len": max_len, "pad": PAD, "bos": BOS,
            "code_token_base": 2,  # token = base + level*codes + code
        }, f, ensure_ascii=False, indent=2)
    verify(k)


def verify(k):
    try:
        import onnxruntime as ort
    except Exception:
        print("(未装 onnxruntime,跳过回读校验)")
        return
    sess = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
    # 动态 N×T:模拟在线 beam(批量 + 不同长度),确保 reshape 等节点不烘焙固定形状
    cases = [
        np.array([[BOS]], dtype="int64"),                                  # N=1,T=1
        np.array([[code_token(0, 1, k), code_token(1, 2, k), BOS]], dtype="int64"),  # N=1,T=3
        np.random.randint(2, 2 + k, size=(8, 7)).astype("int64"),          # N=8,T=7(批量+长序列)
    ]
    for x in cases:
        out = sess.run(None, {"tokens": x})
        print(f"onnxruntime 回读 OK:N={x.shape[0]} T={x.shape[1]} logits shape={out[0].shape}")


if __name__ == "__main__":
    main()
