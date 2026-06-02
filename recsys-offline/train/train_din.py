#!/usr/bin/env python3
"""行为序列建模 · DIN(+ MMoE 多目标头)训练 → ONNX 导出(PyTorch)。

输入:同目录 samples_mt.csv(由 Java 作业 gen-samples-mt 生成),表头:
      label_click, label_like, user_id, item_id, category, <5 稠密>,
      seq_items, seq_cats, seq_len, split
  本脚本在 MMoE 的稠密+稀疏之上,额外吃 seq_items(用户历史 ≥4 物品序列,
  `|` 拼接,oldest→newest),做 DIN target-attention。

模型 = DIN(候选 item 对历史行为做注意力池化)+ MMoE 多目标头(CTR/CVR,ESMM)
  - 候选 item 与序列 item <b>共享同一 item embedding 表</b>(DIN 的前提);
  - attention(候选 e_t,历史 e_i)= MLP([e_t, e_i, e_t-e_i, e_t*e_i]) → 标量,
    pad 位用 seq_len 掩码屏蔽(softmax 前置 -1e9),空序列(seq_len=0)池化向量强制为 0;
  - 拼接 [user_emb, item_emb, cat_emb, pooled_seq, dense] → MMoE 专家/门控 → CTR/CVR。
  - loss = BCE(pCTR, click) + BCE(pCTR·pCVR, like)(ESMM,同 train_mmoe.py)。

跨语言契约(必须与 Java SparseFeatureEncoder / SequenceEncoder 逐位一致):
  user_bucket=user%USER_BUCKETS;item_bucket=item%ITEM_BUCKETS;cat_idx=vocab[cat](0=OOV);
  序列定长 SEQ_LEN,<b>右 padding</b>(有效项在前、pad=0 在后),seq_len 给出有效长度。
导出到 recsys-rank/src/main/resources/model/:
  model_din.onnx(输入 dense[N,5]f32 + sparse[N,3]i64 + seq[N,L]i64 + seq_len[N]i64,
  输出 ctr[N,1]+cvr[N,1])、din_schema.json、din_category_vocab.json。

用法:
    pip install -r requirements-deepfm.txt
    python train_din.py
"""
import json
import os
import sys

import numpy as np
import pandas as pd
import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "samples_mt.csv")
MODEL_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "recsys-rank", "src", "main", "resources", "model"))
MODEL_PATH = os.path.join(MODEL_DIR, "model_din.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "din_schema.json")
VOCAB_PATH = os.path.join(MODEL_DIR, "din_category_vocab.json")

USER_BUCKETS = 5000
ITEM_BUCKETS = 20000
EMBED_DIM = 16
N_EXPERTS = 4
SEQ_LEN = 20  # 与 gen-samples-mt 的 seq-len 上限一致

DENSE_COLS = [
    "item_pop_norm", "item_avg_rating", "user_act_norm", "user_avg_rating", "user_cat_affinity",
]
SPARSE_ORDER = ["user_id", "item_id", "category"]


def build_vocab(categories):
    cats = sorted({c for c in categories if isinstance(c, str) and c != ""})
    return {c: i + 1 for i, c in enumerate(cats)}


def encode_seq(seq_items_col):
    """把 `|` 拼接的 item 序列(oldest→newest)编码成 [N,SEQ_LEN] item 桶(右 pad=0)+ [N] 有效长度。"""
    n = len(seq_items_col)
    seq = np.zeros((n, SEQ_LEN), dtype="int64")
    lens = np.zeros(n, dtype="int64")
    for i, s in enumerate(seq_items_col):
        if not isinstance(s, str) or s == "":
            continue
        ids = [int(x) for x in s.split("|") if x != ""]
        ids = ids[-SEQ_LEN:]  # 只取最近 SEQ_LEN 个(gen 侧已 ≤20,这里再兜底)
        for j, iid in enumerate(ids):  # 右 padding:有效项在前
            seq[i, j] = iid % ITEM_BUCKETS
        lens[i] = len(ids)
    return seq, lens


def main():
    if not os.path.exists(SAMPLES):
        sys.exit(f"找不到 {SAMPLES};请先跑 Java 作业:--job=gen-samples-mt")

    df = pd.read_csv(SAMPLES)
    need = DENSE_COLS + SPARSE_ORDER + ["label_click", "label_like", "seq_items", "split"]
    missing = [c for c in need if c not in df.columns]
    if missing:
        sys.exit(f"samples_mt.csv 缺列 {missing}")
    print(f"样本 {len(df)} 行 | click {df['label_click'].sum()} | like {df['label_like'].sum()}")

    vocab = build_vocab(df["category"])
    cardinalities = [USER_BUCKETS, ITEM_BUCKETS, len(vocab) + 1]
    print(f"类目 vocab {len(vocab)}(+OOV);稀疏基数 = {cardinalities};SEQ_LEN={SEQ_LEN}")

    user = (df["user_id"].astype("int64") % USER_BUCKETS).to_numpy()
    item = (df["item_id"].astype("int64") % ITEM_BUCKETS).to_numpy()
    cat = df["category"].map(lambda c: vocab.get(c, 0) if isinstance(c, str) else 0).to_numpy()
    sparse = np.stack([user, item, cat], axis=1).astype("int64")
    dense = df[DENSE_COLS].to_numpy().astype("float32")
    seq, seq_len = encode_seq(df["seq_items"].tolist())
    click = df["label_click"].to_numpy().astype("float32")
    like = df["label_like"].to_numpy().astype("float32")
    nonempty = int((seq_len > 0).sum())
    print(f"非空序列样本 {nonempty}/{len(df)}({100 * nonempty / len(df):.1f}%),平均长度 {seq_len[seq_len > 0].mean():.1f}")

    rng = np.random.default_rng(42)
    is_train = rng.random(len(df)) < 0.85

    from sklearn.metrics import roc_auc_score
    torch.manual_seed(42)
    model = DIN(cardinalities, n_dense=len(DENSE_COLS), embed_dim=EMBED_DIM, n_experts=N_EXPERTS)

    x_d = torch.from_numpy(dense)
    x_s = torch.from_numpy(sparse)
    x_seq = torch.from_numpy(seq)
    x_len = torch.from_numpy(seq_len)
    y_click = torch.from_numpy(click)
    y_like = torch.from_numpy(like)
    tr = torch.from_numpy(np.where(is_train)[0])
    va = torch.from_numpy(np.where(~is_train)[0])

    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-5)
    bce = nn.BCELoss()
    batch = 2048
    best, best_state, patience, bad = 0.0, None, 3, 0

    for epoch in range(1, 21):
        model.train()
        perm = tr[torch.randperm(len(tr))]
        total = 0.0
        for i in range(0, len(perm), batch):
            idx = perm[i:i + batch]
            opt.zero_grad()
            pctr, pcvr = model(x_d[idx], x_s[idx], x_seq[idx], x_len[idx])
            pctr, pcvr = pctr.squeeze(1), pcvr.squeeze(1)
            loss = bce(pctr, y_click[idx]) + bce((pctr * pcvr).clamp(1e-6, 1 - 1e-6), y_like[idx])
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        model.eval()
        with torch.no_grad():
            pctr_v, pcvr_v = model(x_d[va], x_s[va], x_seq[va], x_len[va])
            pctr_v, pcvr_v = pctr_v.squeeze(1).numpy(), pcvr_v.squeeze(1).numpy()
        auc_click = roc_auc_score(y_click[va].numpy(), pctr_v)
        auc_like = roc_auc_score(y_like[va].numpy(), pctr_v * pcvr_v)
        score = (auc_click + auc_like) / 2
        print(f"epoch {epoch:2d} | loss {total / len(tr):.4f} | "
              f"AUC click(CTR) {auc_click:.4f} | AUC like(CTCVR) {auc_like:.4f}")
        if score > best:
            best, bad = score, 0
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
        else:
            bad += 1
            if bad >= patience:
                print(f"早停({patience} 轮平均 AUC 未提升)")
                break

    if best_state is not None:
        model.load_state_dict(best_state)
    export_onnx(model, vocab, cardinalities)
    print(f"\n✅ 导出完成:\n  {MODEL_PATH}\n  {SCHEMA_PATH}\n  {VOCAB_PATH}")


class DIN(nn.Module):
    """DIN target-attention 池化历史行为 + MMoE 多目标头。"""

    def __init__(self, cardinalities, n_dense, embed_dim, n_experts):
        super().__init__()
        self.n_fields = len(cardinalities)
        self.embed_dim = embed_dim
        self.emb = nn.ModuleList([nn.Embedding(c, embed_dim) for c in cardinalities])
        # item 序列复用 sparse 里的 item embedding(索引 1 = item 字段),不另建表
        self.item_field = SPARSE_ORDER.index("item_id")
        # DIN 注意力 MLP:输入 [e_t, e_i, e_t-e_i, e_t*e_i] = 4*embed_dim
        self.att = nn.Sequential(
            nn.Linear(4 * embed_dim, 32), nn.ReLU(), nn.Linear(32, 1)
        )
        # MMoE:输入 = 稀疏 embedding 展平 ⊕ pooled_seq ⊕ dense
        in_dim = self.n_fields * embed_dim + embed_dim + n_dense
        self.experts = nn.ModuleList([
            nn.Sequential(nn.Linear(in_dim, 64), nn.ReLU(), nn.Linear(64, 32), nn.ReLU())
            for _ in range(n_experts)
        ])
        self.gate_ctr = nn.Linear(in_dim, n_experts)
        self.gate_cvr = nn.Linear(in_dim, n_experts)
        self.tower_ctr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))
        self.tower_cvr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))
        for e in self.emb:
            nn.init.normal_(e.weight, std=0.01)

    def forward(self, dense, sparse, seq, seq_len):
        embeds = [self.emb[j](sparse[:, j]) for j in range(self.n_fields)]
        e_t = embeds[self.item_field]                      # 候选 item embedding [N, d]
        seq_emb = self.emb[self.item_field](seq)           # 序列 item embedding [N, L, d]

        n, length, d = seq_emb.shape
        e_t_exp = e_t.unsqueeze(1).expand(n, length, d)    # [N, L, d]
        att_in = torch.cat([e_t_exp, seq_emb, e_t_exp - seq_emb, e_t_exp * seq_emb], dim=2)
        scores = self.att(att_in).squeeze(2)               # [N, L]
        # 掩码:位置 < seq_len 为有效(右 padding)
        positions = torch.arange(length, device=seq.device).unsqueeze(0)  # [1, L]
        mask = positions < seq_len.unsqueeze(1)            # [N, L] bool
        scores = scores.masked_fill(~mask, -1e9)
        weights = torch.softmax(scores, dim=1).unsqueeze(2)  # [N, L, 1]
        pooled = (weights * seq_emb).sum(dim=1)            # [N, d]
        # 空序列(seq_len=0)softmax 退化为均匀分布,池化无意义 → 强制置 0
        has_seq = (seq_len > 0).float().unsqueeze(1)       # [N, 1]
        pooled = pooled * has_seq

        x = torch.cat(embeds + [pooled, dense], dim=1)
        expert_out = torch.stack([e(x) for e in self.experts], dim=1)  # [N, E, 32]

        def mix(gate):
            w = torch.softmax(gate(x), dim=1).unsqueeze(2)
            return (w * expert_out).sum(dim=1)

        ctr = torch.sigmoid(self.tower_ctr(mix(self.gate_ctr)))
        cvr = torch.sigmoid(self.tower_cvr(mix(self.gate_cvr)))
        return ctr, cvr


def export_onnx(model, vocab, cardinalities):
    os.makedirs(MODEL_DIR, exist_ok=True)
    model.eval()
    d_dense = torch.zeros(2, len(DENSE_COLS), dtype=torch.float32)
    d_sparse = torch.zeros(2, len(SPARSE_ORDER), dtype=torch.int64)
    d_seq = torch.zeros(2, SEQ_LEN, dtype=torch.int64)
    d_len = torch.ones(2, dtype=torch.int64)
    torch.onnx.export(
        model,
        (d_dense, d_sparse, d_seq, d_len),
        MODEL_PATH,
        input_names=["dense", "sparse", "seq", "seq_len"],
        output_names=["ctr", "cvr"],
        dynamic_axes={"dense": {0: "N"}, "sparse": {0: "N"}, "seq": {0: "N"},
                      "seq_len": {0: "N"}, "ctr": {0: "N"}, "cvr": {0: "N"}},
        opset_version=17,
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
            "model": "din",
            "dense_order": DENSE_COLS,
            "sparse_order": SPARSE_ORDER,
            "user_buckets": USER_BUCKETS,
            "item_buckets": ITEM_BUCKETS,
            "seq_len": SEQ_LEN,
            "embed_dim": EMBED_DIM,
            "n_experts": N_EXPERTS,
            "sparse_cardinalities": cardinalities,
            "outputs": ["ctr", "cvr"],
        }, f, ensure_ascii=False, indent=2)
    with open(VOCAB_PATH, "w") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)
    verify()


def verify():
    try:
        import onnxruntime as ort
    except Exception:
        print("(未装 onnxruntime,跳过回读校验)")
        return
    sess = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
    d = np.zeros((3, len(DENSE_COLS)), dtype="float32")
    s = np.zeros((3, len(SPARSE_ORDER)), dtype="int64")
    sq = np.zeros((3, SEQ_LEN), dtype="int64")
    sl = np.array([0, 2, 5], dtype="int64")
    sq[1, :2] = [10, 20]
    sq[2, :5] = [1, 2, 3, 4, 5]
    out = sess.run(None, {"dense": d, "sparse": s, "seq": sq, "seq_len": sl})
    print(f"onnxruntime 回读 OK:输入={[i.name for i in sess.get_inputs()]},"
          f"输出={[o.name for o in sess.get_outputs()]},形状={[o.shape for o in out]}")


if __name__ == "__main__":
    main()
