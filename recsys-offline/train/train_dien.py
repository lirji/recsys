#!/usr/bin/env python3
"""行为序列建模 · DIEN(兴趣演化 + MMoE 多目标头)训练 → ONNX 导出(PyTorch)。

DIEN(Deep Interest Evolution Network,R1)在 DIN 之上把"对候选做静态注意力池化"升级为
**兴趣随时间演化**的两层结构:
  1. <b>兴趣抽取层</b>(GRU):对历史行为 embedding 序列跑 GRU,得每步兴趣状态 h_1..h_T;
  2. <b>兴趣演化层</b>(AUGRU):再跑一层 GRU,但用"候选↔兴趣状态"的注意力分 a_t <b>门控更新门</b>
     (u'_t = a_t · u_t),让与当前候选相关的兴趣主导演化 → 末态即"针对该候选演化后的兴趣";
  3. 拼接 [user_emb, item_emb, cat_emb, 演化兴趣, dense] → MMoE 专家/门控 → CTR/CVR(ESMM)。

<b>在线契约与 DIN 完全一致</b>:同 samples_mt.csv、同 SEQ_LEN/PAL、同 4 输入(dense/sparse/seq/seq_len)
+ 2 输出(ctr/cvr)、同 SparseFeatureEncoder/SequenceEncoder。因此在线 DienRankService 是 DinRankService
的镜像(只换模型文件与 strategy),线上 encoder/契约零改动 —— 这是选 DIEN 作"最干净算法增量"的原因。

导出到 recsys-rank/src/main/resources/model/:model_dien.onnx / dien_schema.json / dien_category_vocab.json。

用法(需 recsys-offline/train/.venv,py3.11 + torch):
    python train_dien.py            # 需先 --job=gen-samples-mt 产出 samples_mt.csv
    python train_dien.py --extended-features
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
MODEL_PATH = os.path.join(MODEL_DIR, "model_dien.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "dien_schema.json")
VOCAB_PATH = os.path.join(MODEL_DIR, "dien_category_vocab.json")

USER_BUCKETS = 5000
ITEM_BUCKETS = 20000
EMBED_DIM = 16
N_EXPERTS = 4
SEQ_LEN = 20
MAX_POSITION = 10

DENSE_COLS = [
    "item_pop_norm", "item_avg_rating", "user_act_norm", "user_avg_rating", "user_cat_affinity",
]
SPARSE_ORDER = ["user_id", "item_id", "category"]
EXTENDED_DENSE = ["user_cat_cnt_norm", "user_cat_ratio", "item_rating_std"]
USE_EXTENDED = "--extended-features" in sys.argv


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
        ids = ids[-SEQ_LEN:]
        for j, iid in enumerate(ids):
            seq[i, j] = iid % ITEM_BUCKETS
        lens[i] = len(ids)
    return seq, lens


def main():
    if not os.path.exists(SAMPLES):
        sys.exit(f"找不到 {SAMPLES};请先跑 Java 作业:--job=gen-samples-mt")

    df = pd.read_csv(SAMPLES)
    if USE_EXTENDED:
        DENSE_COLS.extend(EXTENDED_DENSE)
        print(f"S2 特征扩充:启用,dense_order={DENSE_COLS}")
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
    if "position" in df.columns:
        position = df["position"].fillna(0).clip(0, MAX_POSITION).astype("int64").to_numpy()
    else:
        position = np.zeros(len(df), dtype="int64")
    n_pos = int((position > 0).sum())
    print(f"位置去偏(PAL):有位次样本 {n_pos}/{len(df)}"
          + ("(全 0 → PAL 塔休眠,等价于无去偏)" if n_pos == 0 else ""))
    nonempty = int((seq_len > 0).sum())
    print(f"非空序列样本 {nonempty}/{len(df)}({100 * nonempty / max(1, len(df)):.1f}%)")

    rng = np.random.default_rng(42)
    is_train = rng.random(len(df)) < 0.85

    from sklearn.metrics import roc_auc_score
    torch.manual_seed(42)
    model = DIEN(cardinalities, n_dense=len(DENSE_COLS), embed_dim=EMBED_DIM, n_experts=N_EXPERTS)

    x_d = torch.from_numpy(dense)
    x_s = torch.from_numpy(sparse)
    x_seq = torch.from_numpy(seq)
    x_len = torch.from_numpy(seq_len)
    x_pos = torch.from_numpy(position)
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
            pctr, pcvr = model(x_d[idx], x_s[idx], x_seq[idx], x_len[idx], x_pos[idx])
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


class AUGRUCell(nn.Module):
    """AUGRU:注意力更新门 GRU —— 用注意力分 att 缩放更新门 u'_t = att · u_t,
    让与候选相关的兴趣状态主导演化(DIEN 兴趣演化层)。"""

    def __init__(self, input_size, hidden_size):
        super().__init__()
        self.x2h = nn.Linear(input_size, 3 * hidden_size)
        self.h2h = nn.Linear(hidden_size, 3 * hidden_size)

    def forward(self, x, h, att):
        gx = self.x2h(x)
        gh = self.h2h(h)
        i_r, i_u, i_n = gx.chunk(3, dim=1)
        h_r, h_u, h_n = gh.chunk(3, dim=1)
        r = torch.sigmoid(i_r + h_r)
        u = torch.sigmoid(i_u + h_u)
        n = torch.tanh(i_n + r * h_n)
        u = att * u                          # 注意力门控更新门
        return (1 - u) * h + u * n


class DIEN(nn.Module):
    """兴趣抽取(GRUCell)+ 兴趣演化(AUGRU,注意力门控)+ MMoE 多目标头。
    时间步循环按固定 SEQ_LEN 展开,导出 ONNX 为静态图(batch 轴动态)。"""

    def __init__(self, cardinalities, n_dense, embed_dim, n_experts):
        super().__init__()
        self.n_fields = len(cardinalities)
        self.embed_dim = embed_dim
        self.emb = nn.ModuleList([nn.Embedding(c, embed_dim) for c in cardinalities])
        self.item_field = SPARSE_ORDER.index("item_id")
        # 兴趣抽取层:标准 GRUCell
        self.extract = nn.GRUCell(embed_dim, embed_dim)
        # target-attention(候选 ↔ 兴趣状态):MLP([e_t, h_i, e_t-h_i, e_t*h_i]) → 标量
        self.att = nn.Sequential(nn.Linear(4 * embed_dim, 32), nn.ReLU(), nn.Linear(32, 1))
        # 兴趣演化层:AUGRU
        self.augru = AUGRUCell(embed_dim, embed_dim)
        # MMoE:输入 = 稀疏 embedding 展平 ⊕ 演化兴趣(embed_dim) ⊕ dense
        in_dim = self.n_fields * embed_dim + embed_dim + n_dense
        self.experts = nn.ModuleList([
            nn.Sequential(nn.Linear(in_dim, 64), nn.ReLU(), nn.Linear(64, 32), nn.ReLU())
            for _ in range(n_experts)
        ])
        self.gate_ctr = nn.Linear(in_dim, n_experts)
        self.gate_cvr = nn.Linear(in_dim, n_experts)
        self.tower_ctr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))
        self.tower_cvr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))
        self.pos_bias = nn.Embedding(MAX_POSITION + 1, 1)
        nn.init.zeros_(self.pos_bias.weight)
        for e in self.emb:
            nn.init.normal_(e.weight, std=0.01)

    def forward(self, dense, sparse, seq, seq_len, position=None):
        embeds = [self.emb[j](sparse[:, j]) for j in range(self.n_fields)]
        e_t = embeds[self.item_field]                       # 候选 item [N, d]
        seq_emb = self.emb[self.item_field](seq)            # 序列 item [N, L, d]
        n, length, d = seq_emb.shape

        positions = torch.arange(length, device=seq.device).unsqueeze(0)  # [1, L]
        maskb = positions < seq_len.unsqueeze(1)            # [N, L] bool
        maskf = maskb.float()

        # 1. 兴趣抽取:GRUCell 逐步,padding 步保持 h 不变
        h = torch.zeros(n, d, device=seq.device)
        states = []
        for t in range(length):
            h_new = self.extract(seq_emb[:, t, :], h)
            m = maskf[:, t:t + 1]
            h = m * h_new + (1 - m) * h
            states.append(h)
        interest = torch.stack(states, dim=1)               # [N, L, d]

        # 2. target-attention:候选 ↔ 各步兴趣状态
        e_t_exp = e_t.unsqueeze(1).expand(n, length, d)
        att_in = torch.cat([e_t_exp, interest, e_t_exp - interest, e_t_exp * interest], dim=2)
        scores = self.att(att_in).squeeze(2)                # [N, L]
        scores = scores.masked_fill(~maskb, -1e9)
        att_w = torch.softmax(scores, dim=1)                # [N, L] 注意力权重

        # 3. 兴趣演化:AUGRU 用注意力门控更新门,padding 步保持
        h2 = torch.zeros(n, d, device=seq.device)
        for t in range(length):
            h2_new = self.augru(interest[:, t, :], h2, att_w[:, t:t + 1])
            m = maskf[:, t:t + 1]
            h2 = m * h2_new + (1 - m) * h2
        has_seq = (seq_len > 0).float().unsqueeze(1)        # 空序列 → 演化兴趣置 0
        evolved = h2 * has_seq

        x = torch.cat(embeds + [evolved, dense], dim=1)
        expert_out = torch.stack([e(x) for e in self.experts], dim=1)

        def mix(gate):
            w = torch.softmax(gate(x), dim=1).unsqueeze(2)
            return (w * expert_out).sum(dim=1)

        ctr_logit = self.tower_ctr(mix(self.gate_ctr))
        if position is not None:
            pm = (position > 0).float().unsqueeze(1)
            ctr_logit = ctr_logit + self.pos_bias(position) * pm
        ctr = torch.sigmoid(ctr_logit)
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
        dynamo=False,   # 用 TorchScript 导出器:AUGRU 的 .chunk→Split 才符合 opset 17(dynamo 会带 opset18 的 num_outputs)
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
            "model": "dien",
            "dense_order": DENSE_COLS,
            "sparse_order": SPARSE_ORDER,
            "user_buckets": USER_BUCKETS,
            "item_buckets": ITEM_BUCKETS,
            "seq_len": SEQ_LEN,
            "embed_dim": EMBED_DIM,
            "n_experts": N_EXPERTS,
            "sparse_cardinalities": cardinalities,
            "max_position": MAX_POSITION,
            "position_debiased": True,
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
