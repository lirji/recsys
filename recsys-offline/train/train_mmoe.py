#!/usr/bin/env python3
"""多目标排序 · MMoE + ESMM 训练 → ONNX 导出(PyTorch)。

输入:同目录 samples_mt.csv(由 Java 作业 gen-samples-mt 生成),表头:
      label_click, label_like, user_id, item_id, category, <5 稠密特征>,
      seq_items, seq_cats, seq_len, position, split
  本脚本只用 label_click/label_like + 稠密 + 稀疏(user_id/item_id/category) + position,
  序列列留给 DIN(train_din.py),这里忽略。

位置去偏(PAL,Position-aware Learning):训练时 pCTR = sigmoid(相关性 logit + 位置偏置 b(position)),
  位置偏置塔吃 position、拟合"观测点击"里的位置效应;**导出时 position=None,该塔不入计算图**,
  ONNX 仍是 dense+sparse 双输入(线上推理契约/编码器一概不变)。position=0 表示位次未知(本仓历史样本
  无真实曝光位次,默认全 0 → PAL 休眠,精度等同无去偏);用 gen-samples-mt --position-proxy 可注入
  热度名次代理来观察机制。

模型 = MMoE(多门控混合专家)+ ESMM(整体空间多任务)
  - 输入 = 稀疏 embedding(user/item/category)展平 ⊕ 5 个稠密特征;
  - 共享底层 N 个专家(MLP);两个任务(CTR / CVR)各一个门控(对专家做 softmax 加权);
  - CTR 头 → pCTR = P(click);CVR 头 → pCVR = P(like | click);
  - ESMM 关键:pCTCVR = pCTR · pCVR 在<全样本>上对 like 标签学习,
    pCTR 在全样本上对 click 标签学习 —— CVR 塔不直接见「曝光未点击」样本,
    从而消除 CVR 的样本选择偏差。
    loss = BCE(pCTR, click) + BCE(pCTCVR, like)。

跨语言编码契约(必须与 Java SparseFeatureEncoder 逐位一致,同 DeepFM):
  user_bucket = user_id % USER_BUCKETS;item_bucket = item_id % ITEM_BUCKETS;
  cat_idx = category_vocab[category](0 = 未登录/缺失)。
导出到 recsys-rank/src/main/resources/model/:
  model_mmoe.onnx(双输入 dense[N,5] float32 + sparse[N,3] int64,双输出 ctr[N,1]+cvr[N,1])、
  mmoe_schema.json、mmoe_category_vocab.json。

用法:
    pip install -r requirements-deepfm.txt   # 复用 torch + onnxscript
    python train_mmoe.py
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
MODEL_PATH = os.path.join(MODEL_DIR, "model_mmoe.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "mmoe_schema.json")
VOCAB_PATH = os.path.join(MODEL_DIR, "mmoe_category_vocab.json")

# 稀疏编码契约(改这里必须同步 Java SparseFeatureEncoder)
USER_BUCKETS = 5000
ITEM_BUCKETS = 20000
EMBED_DIM = 16
N_EXPERTS = 4
MAX_POSITION = 10  # 位置去偏(PAL):position ∈ [0, MAX_POSITION],0=未知(不施偏置)

# 稠密特征列顺序(= Java FeatureAssembler.FEATURE_ORDER,逐位对齐)
DENSE_COLS = [
    "item_pop_norm",
    "item_avg_rating",
    "user_act_norm",
    "user_avg_rating",
    "user_cat_affinity",
]
SPARSE_ORDER = ["user_id", "item_id", "category"]
# S2 特征扩充:--extended-features 时 dense 用 8 维(需 gen-samples-mt --extended-features 产出对应列)
EXTENDED_DENSE = ["user_cat_cnt_norm", "user_cat_ratio", "item_rating_std"]
USE_EXTENDED = "--extended-features" in sys.argv


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
    if USE_EXTENDED:
        DENSE_COLS.extend(EXTENDED_DENSE)
        print(f"S2 特征扩充:启用,dense_order={DENSE_COLS}")
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
    # 位置去偏(PAL):训练用 position-bias 塔吃 position 解释掉位置效应,导出时丢弃 → 线上推理契约不变
    if "position" in df.columns:
        position = df["position"].fillna(0).clip(0, MAX_POSITION).astype("int64").to_numpy()
    else:
        position = np.zeros(len(df), dtype="int64")
    n_pos = int((position > 0).sum())
    print(f"位置去偏(PAL):有位次样本 {n_pos}/{len(df)}"
          + ("(全 0 → PAL 塔休眠,等价于无去偏)" if n_pos == 0 else ""))

    # id-embedding 模型用随机切分(同 DeepFM:保证每个 id 在 train 见过)
    rng = np.random.default_rng(42)
    is_train = rng.random(len(df)) < 0.85

    from sklearn.metrics import roc_auc_score

    torch.manual_seed(42)
    model = MMoE(cardinalities, n_dense=len(DENSE_COLS), embed_dim=EMBED_DIM, n_experts=N_EXPERTS)

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
            # 训练:带 position → pCTR 含位置偏置(拟合"观测"点击);CVR 不受位置影响
            pctr, pcvr = model(x_d[idx], x_s[idx], x_pos[idx])
            pctr, pcvr = pctr.squeeze(1), pcvr.squeeze(1)
            pctcvr = pctr * pcvr
            # ESMM:CTR 用 click 标签,CTCVR 用 like 标签(都在全样本上)
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
        auc_like = roc_auc_score(y_like[va].numpy(), ctcvr_v)  # CTCVR 对 like
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


class MMoE(nn.Module):
    """MMoE:共享专家 + 每任务门控 → CTR / CVR 双塔。"""

    def __init__(self, cardinalities, n_dense, embed_dim, n_experts):
        super().__init__()
        self.n_fields = len(cardinalities)
        self.n_experts = n_experts
        self.emb = nn.ModuleList([nn.Embedding(c, embed_dim) for c in cardinalities])
        in_dim = self.n_fields * embed_dim + n_dense
        # 共享专家
        self.experts = nn.ModuleList([
            nn.Sequential(nn.Linear(in_dim, 64), nn.ReLU(), nn.Linear(64, 32), nn.ReLU())
            for _ in range(n_experts)
        ])
        # 每任务一个门控(对专家做 softmax)
        self.gate_ctr = nn.Linear(in_dim, n_experts)
        self.gate_cvr = nn.Linear(in_dim, n_experts)
        # 每任务一个塔
        self.tower_ctr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))
        self.tower_cvr = nn.Sequential(nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))
        # PAL 位置偏置塔:logit 空间加性偏置 b(position),只在训练用、导出丢弃(position=None);
        # 0 初始化 + 对 position==0(未知)掩码为 0 → 不污染相关性头的服务分。
        self.pos_bias = nn.Embedding(MAX_POSITION + 1, 1)
        nn.init.zeros_(self.pos_bias.weight)
        for e in self.emb:
            nn.init.normal_(e.weight, std=0.01)

    def forward(self, dense, sparse, position=None):
        embeds = [self.emb[j](sparse[:, j]) for j in range(self.n_fields)]
        x = torch.cat(embeds + [dense], dim=1)                       # [N, in_dim]
        expert_out = torch.stack([e(x) for e in self.experts], dim=1)  # [N, E, 32]

        def mix(gate):
            w = torch.softmax(gate(x), dim=1).unsqueeze(2)           # [N, E, 1]
            return (w * expert_out).sum(dim=1)                       # [N, 32]

        ctr_logit = self.tower_ctr(mix(self.gate_ctr))               # [N, 1] 相关性 logit
        if position is not None:                                     # 训练:加位置偏置(导出时 position=None,此分支不入图)
            mask = (position > 0).float().unsqueeze(1)               # [N, 1] 未知位次不施偏置
            ctr_logit = ctr_logit + self.pos_bias(position) * mask
        ctr = torch.sigmoid(ctr_logit)                               # [N, 1]
        cvr = torch.sigmoid(self.tower_cvr(mix(self.gate_cvr)))      # [N, 1]
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
    # 同 DeepFM:合并外置权重为单一自包含 .onnx,并把 IR 降到 9(Java onnxruntime 上限)
    import onnx
    m = onnx.load(MODEL_PATH)
    m.ir_version = 9
    onnx.save_model(m, MODEL_PATH, save_as_external_data=False)
    data_file = MODEL_PATH + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)
    with open(SCHEMA_PATH, "w") as f:
        json.dump({
            "model": "mmoe",
            "dense_order": DENSE_COLS,
            "sparse_order": SPARSE_ORDER,
            "user_buckets": USER_BUCKETS,
            "item_buckets": ITEM_BUCKETS,
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
    out = sess.run(None, {"dense": d, "sparse": s})
    print(f"onnxruntime 回读 OK:输入={[i.name for i in sess.get_inputs()]},"
          f"输出={[o.name for o in sess.get_outputs()]},形状={[o.shape for o in out]}")


if __name__ == "__main__":
    main()
