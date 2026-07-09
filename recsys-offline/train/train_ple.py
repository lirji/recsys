#!/usr/bin/env python3
"""多目标排序 · PLE(Progressive Layered Extraction / CGC)+ ESMM 训练 → ONNX 导出(PyTorch)。

PLE 相对 MMoE 的改进(治多任务「跷跷板」负迁移):
  MMoE 所有专家被所有任务共享,任务冲突时梯度互相拉扯。PLE 把专家分成
  「共享专家」+「每任务专属专家」,每个任务的门控**只看自己的专属专家 + 共享专家**
  (看不到别的任务的专属专家),从而在保留共享的同时给每个任务留独立通道,减少负迁移。
  本脚本实现单层 CGC(Customized Gating Control,即 1 层 extraction 的 PLE,2 任务足够);
  多层 PLE = 堆叠多个 CGC,可按需扩展。

与 train_mmoe.py **完全相同**的部分(保证可直接替换 / 公平对比):
  - 输入契约:samples_mt.csv,稠密 5 列(=Java FeatureAssembler.FEATURE_ORDER)+
    稀疏 3 列(user_id/item_id/category,与 Java SparseFeatureEncoder 逐位一致);
  - ESMM:loss = BCE(pCTR, click) + BCE(pCTR·pCVR, like),CVR 塔不直接见「曝光未点击」;
  - 位置去偏 PAL:训练加位置偏置塔 b(position),**导出时 position=None 该塔不入图**,
    ONNX 仍是 dense+sparse 双输入(线上编码器/契约一概不变);
  - 导出:双输入 dense[N,5]+sparse[N,3]、双输出 ctr[N,1]+cvr[N,1]、IR9、单一自包含 .onnx。

导出到 recsys-rank/src/main/resources/model/:
  model_ple.onnx、ple_schema.json、ple_category_vocab.json(文件名与 mmoe 区分,可并存对比)。
在线由 PleRankService 加载(recsys.rank.strategy=ple);eval/tune-fusion 用 --rank-strategy=ple。

用法:
    pip install -r requirements-deepfm.txt   # 复用 torch + onnxscript
    python train_ple.py
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
MODEL_PATH = os.path.join(MODEL_DIR, "model_ple.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "ple_schema.json")
VOCAB_PATH = os.path.join(MODEL_DIR, "ple_category_vocab.json")

# 稀疏编码契约(改这里必须同步 Java SparseFeatureEncoder;与 MMoE/DeepFM 一致)
USER_BUCKETS = 5000
ITEM_BUCKETS = 20000
EMBED_DIM = 16
N_SHARED = 2       # 共享专家数
N_SPECIFIC = 2     # 每任务专属专家数
MAX_POSITION = 10  # PAL:position ∈ [0, MAX_POSITION],0=未知(不施偏置)

DENSE_COLS = [
    "item_pop_norm",
    "item_avg_rating",
    "user_act_norm",
    "user_avg_rating",
    "user_cat_affinity",
]
SPARSE_ORDER = ["user_id", "item_id", "category"]


def build_vocab(categories):
    cats = sorted({c for c in categories if isinstance(c, str) and c != ""})
    return {c: i + 1 for i, c in enumerate(cats)}


def encode_sparse(df, vocab):
    user = (df["user_id"].astype("int64") % USER_BUCKETS).to_numpy()
    item = (df["item_id"].astype("int64") % ITEM_BUCKETS).to_numpy()
    cat = df["category"].map(lambda c: vocab.get(c, 0) if isinstance(c, str) else 0).to_numpy()
    return np.stack([user, item, cat], axis=1).astype("int64")


def main():
    if not os.path.exists(SAMPLES):
        sys.exit(f"找不到 {SAMPLES};请先跑 Java 作业:--job=gen-samples-mt")

    df = pd.read_csv(SAMPLES)
    need = DENSE_COLS + SPARSE_ORDER + ["label_click", "label_like", "split"]
    missing = [c for c in need if c not in df.columns]
    if missing:
        sys.exit(f"samples_mt.csv 缺列 {missing};请用最新 gen-samples-mt 重新生成")
    print(f"样本 {len(df)} 行 | click {df['label_click'].sum()} | like {df['label_like'].sum()}")

    vocab = build_vocab(df["category"])
    cat_card = len(vocab) + 1
    cardinalities = [USER_BUCKETS, ITEM_BUCKETS, cat_card]
    print(f"类目 vocab 大小 {len(vocab)}(+OOV);稀疏基数 user/item/cat = {cardinalities}")

    sparse = encode_sparse(df, vocab)
    dense = df[DENSE_COLS].to_numpy().astype("float32")
    click = df["label_click"].to_numpy().astype("float32")
    like = df["label_like"].to_numpy().astype("float32")
    if "position" in df.columns:
        position = df["position"].fillna(0).clip(0, MAX_POSITION).astype("int64").to_numpy()
    else:
        position = np.zeros(len(df), dtype="int64")
    n_pos = int((position > 0).sum())
    print(f"位置去偏(PAL):有位次样本 {n_pos}/{len(df)}"
          + ("(全 0 → PAL 塔休眠,等价于无去偏)" if n_pos == 0 else ""))

    rng = np.random.default_rng(42)
    is_train = rng.random(len(df)) < 0.85

    from sklearn.metrics import roc_auc_score

    torch.manual_seed(42)
    model = PLE(cardinalities, n_dense=len(DENSE_COLS), embed_dim=EMBED_DIM,
                n_shared=N_SHARED, n_specific=N_SPECIFIC)

    x_d = torch.from_numpy(dense)
    x_s = torch.from_numpy(sparse)
    x_pos = torch.from_numpy(position)
    y_click = torch.from_numpy(click)
    y_like = torch.from_numpy(like)
    tr = torch.from_numpy(np.where(is_train)[0])
    va = torch.from_numpy(np.where(~is_train)[0])

    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-5)
    bce = nn.BCELoss()
    batch = 2048
    best_auc, best_state, patience, bad = 0.0, None, 3, 0

    for epoch in range(1, 21):
        model.train()
        perm = tr[torch.randperm(len(tr))]
        total = 0.0
        for i in range(0, len(perm), batch):
            idx = perm[i:i + batch]
            opt.zero_grad()
            pctr, pcvr = model(x_d[idx], x_s[idx], x_pos[idx])
            pctr, pcvr = pctr.squeeze(1), pcvr.squeeze(1)
            pctcvr = pctr * pcvr
            loss = bce(pctr, y_click[idx]) + bce(pctcvr.clamp(1e-6, 1 - 1e-6), y_like[idx])
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        model.eval()
        with torch.no_grad():
            pctr_v, pcvr_v = model(x_d[va], x_s[va])
            pctr_v, pcvr_v = pctr_v.squeeze(1).numpy(), pcvr_v.squeeze(1).numpy()
        ctcvr_v = pctr_v * pcvr_v
        auc_click = roc_auc_score(y_click[va].numpy(), pctr_v)
        auc_like = roc_auc_score(y_like[va].numpy(), ctcvr_v)
        score = (auc_click + auc_like) / 2
        print(f"epoch {epoch:2d} | loss {total / len(tr):.4f} | "
              f"AUC click(CTR) {auc_click:.4f} | AUC like(CTCVR) {auc_like:.4f}")
        if score > best_auc:
            best_auc, bad = score, 0
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


class Expert(nn.Module):
    """单个专家 MLP(与 MMoE 专家同结构,便于公平对比)。"""

    def __init__(self, in_dim):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(in_dim, 64), nn.ReLU(), nn.Linear(64, 32), nn.ReLU())

    def forward(self, x):
        return self.net(x)


class PLE(nn.Module):
    """单层 CGC(PLE):共享专家 + CTR/CVR 各自专属专家;每任务门控只看「自己专属 + 共享」。"""

    def __init__(self, cardinalities, n_dense, embed_dim, n_shared, n_specific):
        super().__init__()
        self.n_fields = len(cardinalities)
        self.emb = nn.ModuleList([nn.Embedding(c, embed_dim) for c in cardinalities])
        in_dim = self.n_fields * embed_dim + n_dense

        # 三组专家:共享 / CTR 专属 / CVR 专属
        self.shared = nn.ModuleList([Expert(in_dim) for _ in range(n_shared)])
        self.ctr_experts = nn.ModuleList([Expert(in_dim) for _ in range(n_specific)])
        self.cvr_experts = nn.ModuleList([Expert(in_dim) for _ in range(n_specific)])

        # 每任务门控:输出维度 = 自己专属数 + 共享数(只对这两组做 softmax 加权)
        self.gate_ctr = nn.Linear(in_dim, n_specific + n_shared)
        self.gate_cvr = nn.Linear(in_dim, n_specific + n_shared)

        self.tower_ctr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))
        self.tower_cvr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))

        # PAL 位置偏置塔:训练用、导出丢弃;0 初始化 + position==0 掩码
        self.pos_bias = nn.Embedding(MAX_POSITION + 1, 1)
        nn.init.zeros_(self.pos_bias.weight)
        for e in self.emb:
            nn.init.normal_(e.weight, std=0.01)

    def _cgc(self, x, specific_experts, gate):
        # 该任务可见的专家 = 自己专属 + 共享(看不到另一个任务的专属专家)
        outs = [e(x) for e in specific_experts] + [e(x) for e in self.shared]
        stack = torch.stack(outs, dim=1)                    # [N, (spec+shared), 32]
        w = torch.softmax(gate(x), dim=1).unsqueeze(2)      # [N, (spec+shared), 1]
        return (w * stack).sum(dim=1)                       # [N, 32]

    def forward(self, dense, sparse, position=None):
        embeds = [self.emb[j](sparse[:, j]) for j in range(self.n_fields)]
        x = torch.cat(embeds + [dense], dim=1)              # [N, in_dim]

        ctr_logit = self.tower_ctr(self._cgc(x, self.ctr_experts, self.gate_ctr))
        if position is not None:                            # 训练:加位置偏置(导出 position=None,此分支不入图)
            mask = (position > 0).float().unsqueeze(1)
            ctr_logit = ctr_logit + self.pos_bias(position) * mask
        ctr = torch.sigmoid(ctr_logit)
        cvr = torch.sigmoid(self.tower_cvr(self._cgc(x, self.cvr_experts, self.gate_cvr)))
        return ctr, cvr


def export_onnx(model, vocab, cardinalities):
    os.makedirs(MODEL_DIR, exist_ok=True)
    model.eval()
    dummy_dense = torch.zeros(2, len(DENSE_COLS), dtype=torch.float32)
    dummy_sparse = torch.zeros(2, len(SPARSE_ORDER), dtype=torch.int64)
    torch.onnx.export(
        model,
        (dummy_dense, dummy_sparse),
        MODEL_PATH,
        input_names=["dense", "sparse"],
        output_names=["ctr", "cvr"],
        dynamic_axes={"dense": {0: "N"}, "sparse": {0: "N"}, "ctr": {0: "N"}, "cvr": {0: "N"}},
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
            "model": "ple",
            "dense_order": DENSE_COLS,
            "sparse_order": SPARSE_ORDER,
            "user_buckets": USER_BUCKETS,
            "item_buckets": ITEM_BUCKETS,
            "embed_dim": EMBED_DIM,
            "n_shared": N_SHARED,
            "n_specific": N_SPECIFIC,
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
    out = sess.run(None, {"dense": d, "sparse": s})
    print(f"onnxruntime 回读 OK:输入={[i.name for i in sess.get_inputs()]},"
          f"输出={[o.name for o in sess.get_outputs()]},形状={[o.shape for o in out]}")


if __name__ == "__main__":
    main()
