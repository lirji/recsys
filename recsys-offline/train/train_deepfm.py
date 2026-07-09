#!/usr/bin/env python3
"""深度排序 · DeepFM CTR 训练 → ONNX 导出(PyTorch)。

输入:同目录 samples.csv(由 Java 作业 gen-samples 生成),表头:
      label, user_id, item_id, category, <5 稠密特征>, split

DeepFM = sigmoid( 一阶线性 + FM 二阶交叉 + DNN )
  - 稀疏字段:user_id / item_id / category 各过 embedding(FM 二阶 + DNN 共用),
    外加每字段一维 embedding 做一阶线性项;
  - 稠密字段:5 个连续特征(与 LightGBM 同源,经 Java FeatureAssembler 装配),
    走一阶线性 + 拼进 DNN。

跨语言编码契约(必须与 Java SparseFeatureEncoder 逐位一致):
  - user_bucket = user_id % USER_BUCKETS
  - item_bucket = item_id % ITEM_BUCKETS
  - cat_idx     = category_vocab[category](0 = 未登录/缺失)
导出三件套到 recsys-rank/src/main/resources/model/:
  model_deepfm.onnx(双输入 dense[N,5] float32 + sparse[N,3] int64,输出 ctr[N,1])、
  rank_schema.json(桶大小/字段顺序/基数)、category_vocab.json(类目→索引)。

用法:
    pip install -r requirements-deepfm.txt
    python train_deepfm.py
"""
import json
import os
import sys

import numpy as np
import pandas as pd
import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "samples.csv")
MODEL_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "recsys-rank", "src", "main", "resources", "model"))
MODEL_PATH = os.path.join(MODEL_DIR, "model_deepfm.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "rank_schema.json")
VOCAB_PATH = os.path.join(MODEL_DIR, "category_vocab.json")
SEMANTIC_ID_CSV = os.path.join(HERE, "item_semantic_id.csv")           # train_rqvae.py 产出
SEMANTIC_ID_MAP_PATH = os.path.join(MODEL_DIR, "semantic_id_map.json")  # 在线 SparseFeatureEncoder 读

# 稀疏编码契约(改这里必须同步 Java SparseFeatureEncoder)
USER_BUCKETS = 5000
ITEM_BUCKETS = 20000
EMBED_DIM = 16
# 语义 ID 稀疏特征(可选,--semantic-id 开启):把 RQ-VAE 生成的 (c0,c1,c2) 当稀疏字段进精排,
# 让精排用上生成式召回的语义信号。每层 codebook 大小 K=256(与 train_rqvae.py 默认一致),共 3 层。
SEMANTIC_LEVELS = 3
SEMANTIC_CARD = 256
USE_SEMANTIC_ID = "--semantic-id" in sys.argv
ZERO_SID = [0, 0, 0]

# 稠密特征列顺序(= Java FeatureAssembler.FEATURE_ORDER,逐位对齐)
DENSE_COLS = [
    "item_pop_norm",
    "item_avg_rating",
    "user_act_norm",
    "user_avg_rating",
    "user_cat_affinity",
]
SPARSE_ORDER = ["user_id", "item_id", "category"]


def build_vocab(categories):
    """类目 → 索引;0 留给未登录/缺失,真实类目从 1 开始(按字典序稳定)。"""
    cats = sorted({c for c in categories if isinstance(c, str) and c != ""})
    return {c: i + 1 for i, c in enumerate(cats)}


def load_semantic_map():
    """读 item_semantic_id.csv(item_id,c0,c1,c2)→ {item_id: [c0,c1,c2]}。"""
    sdf = pd.read_csv(SEMANTIC_ID_CSV)
    return {int(r["item_id"]): [int(r["c0"]), int(r["c1"]), int(r["c2"])] for _, r in sdf.iterrows()}


def encode_sparse(df, vocab, sid_map=None):
    user = (df["user_id"].astype("int64") % USER_BUCKETS).to_numpy()
    item = (df["item_id"].astype("int64") % ITEM_BUCKETS).to_numpy()
    cat = df["category"].map(lambda c: vocab.get(c, 0) if isinstance(c, str) else 0).to_numpy()
    cols = [user, item, cat]
    if sid_map is not None:  # 追加 c0,c1,c2(缺失 item → 0,0,0),顺序与 Java 编码器一致
        items = df["item_id"].astype("int64").to_numpy()
        for lvl in range(SEMANTIC_LEVELS):
            cols.append(np.array([sid_map.get(int(it), ZERO_SID)[lvl] for it in items], dtype="int64"))
    return np.stack(cols, axis=1).astype("int64")


def main():
    if not os.path.exists(SAMPLES):
        sys.exit(f"找不到 {SAMPLES};请先跑 Java 作业:--job=build-features 再 --job=gen-samples")

    df = pd.read_csv(SAMPLES)
    missing = [c for c in DENSE_COLS + SPARSE_ORDER + ["label", "split"] if c not in df.columns]
    if missing:
        sys.exit(f"samples.csv 缺列 {missing};请用最新 gen-samples 重新生成(已含 user_id/item_id/category)")
    print(f"样本 {len(df)} 行 | label 分布 {df['label'].value_counts().to_dict()} "
          f"| split {df['split'].value_counts().to_dict()}")

    sid_map = None
    if USE_SEMANTIC_ID:
        if not os.path.exists(SEMANTIC_ID_CSV):
            sys.exit(f"--semantic-id 需要 {SEMANTIC_ID_CSV};先跑 train_rqvae.py 生成语义 ID")
        sid_map = load_semantic_map()
        SPARSE_ORDER.extend([f"c{lvl}" for lvl in range(SEMANTIC_LEVELS)])
        print(f"语义 ID 稀疏特征:启用({len(sid_map)} 个 item);sparse_order={SPARSE_ORDER}")

    vocab = build_vocab(df["category"])
    cat_card = len(vocab) + 1  # +1 给 OOV(0)
    cardinalities = [USER_BUCKETS, ITEM_BUCKETS, cat_card]
    if sid_map is not None:
        cardinalities += [SEMANTIC_CARD] * SEMANTIC_LEVELS
    print(f"类目 vocab 大小 {len(vocab)}(+OOV);稀疏基数 = {cardinalities}")

    sparse = encode_sparse(df, vocab, sid_map)
    dense = df[DENSE_COLS].to_numpy().astype("float32")
    label = df["label"].to_numpy().astype("float32")

    # 切分:深度模型用 id embedding,需保证每个 id 在 train 出现过(否则 valid 的冷 id
    # embedding 是随机初始噪声)。故这里用随机切分(CTR benchmark 如 Criteo/Avazu 的标准做法),
    # 而非沿用 CSV 里给 LightGBM 用的时间切分——时间切分会让晚期(偏热门)正例撞上被
    # 「热门=负采样」带偏的 embedding,导致 AUC 反转。两条训练路各取所需,互不影响。
    rng = np.random.default_rng(42)
    is_train = rng.random(len(df)) < 0.85

    from sklearn.metrics import roc_auc_score, log_loss

    torch.manual_seed(42)
    device = torch.device("cpu")  # 数据集小,CPU 训练即可;导出也在 CPU

    model = DeepFM(cardinalities, n_dense=len(DENSE_COLS), embed_dim=EMBED_DIM).to(device)

    x_d = torch.from_numpy(dense)
    x_s = torch.from_numpy(sparse)
    y = torch.from_numpy(label)
    tr = torch.from_numpy(np.where(is_train)[0])
    va = torch.from_numpy(np.where(~is_train)[0])

    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-5)
    loss_fn = torch.nn.BCELoss()
    batch = 1024
    best_auc, best_state, patience, bad = 0.0, None, 3, 0

    for epoch in range(1, 21):
        model.train()
        perm = tr[torch.randperm(len(tr))]
        total = 0.0
        for i in range(0, len(perm), batch):
            idx = perm[i:i + batch]
            opt.zero_grad()
            p = model(x_d[idx], x_s[idx]).squeeze(1)
            loss = loss_fn(p, y[idx])
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        # 验证
        model.eval()
        with torch.no_grad():
            pv = model(x_d[va], x_s[va]).squeeze(1).numpy()
        yv = y[va].numpy()
        auc = roc_auc_score(yv, pv)
        print(f"epoch {epoch:2d} | train_loss {total / len(tr):.4f} | valid_auc {auc:.4f}")
        if auc > best_auc:
            best_auc, bad = auc, 0
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
        else:
            bad += 1
            if bad >= patience:
                print(f"早停(valid_auc {patience} 轮未提升)")
                break

    if best_state is not None:
        model.load_state_dict(best_state)
    model.eval()
    with torch.no_grad():
        pv = model(x_d[va], x_s[va]).squeeze(1).numpy()
        pt = model(x_d[tr], x_s[tr]).squeeze(1).numpy()
    yv = y[va].numpy()
    auc = roc_auc_score(yv, pv)
    ll = log_loss(yv, pv, labels=[0, 1])
    train_auc = roc_auc_score(y[tr].numpy(), pt)
    print(f"\n==== 最优模型 ====\ntrain_auc = {train_auc:.4f}(过拟合自检)"
          f"\nvalid_auc = {auc:.4f}(valid {len(va)} 行)\nLogLoss   = {ll:.4f}")

    export_onnx(model, vocab, cardinalities, sid_map)
    print(f"\n✅ 导出完成:\n  {MODEL_PATH}\n  {SCHEMA_PATH}\n  {VOCAB_PATH}"
          + (f"\n  {SEMANTIC_ID_MAP_PATH}" if sid_map is not None else ""))


class DeepFM(nn.Module):
    """DeepFM:一阶线性 + FM 二阶交叉(稀疏 embedding)+ DNN(稀疏 embedding 拼稠密)。"""

    def __init__(self, cardinalities, n_dense, embed_dim):
        super().__init__()
        self.n_fields = len(cardinalities)
        self.embed_dim = embed_dim
        # FM/DNN 共用的 embedding(每字段 embed_dim 维)
        self.emb = nn.ModuleList([nn.Embedding(c, embed_dim) for c in cardinalities])
        # 一阶线性的 embedding(每字段 1 维)
        self.lin_emb = nn.ModuleList([nn.Embedding(c, 1) for c in cardinalities])
        self.dense_linear = nn.Linear(n_dense, 1)
        self.bias = nn.Parameter(torch.zeros(1))
        self.dnn = nn.Sequential(
            nn.Linear(self.n_fields * embed_dim + n_dense, 128),
            nn.ReLU(),
            nn.Dropout(0.2),
            nn.Linear(128, 64),
            nn.ReLU(),
            nn.Linear(64, 1),
        )
        for e in self.emb:
            nn.init.normal_(e.weight, std=0.01)
        for e in self.lin_emb:
            nn.init.zeros_(e.weight)

    def forward(self, dense, sparse):
        # 各字段 embedding → [N, n_fields, k]
        embeds = torch.stack([self.emb[j](sparse[:, j]) for j in range(self.n_fields)], dim=1)
        # FM 二阶:0.5 * sum_k((sum_i e_i)^2 - sum_i e_i^2)
        sum_sq = embeds.sum(dim=1) ** 2
        sq_sum = (embeds ** 2).sum(dim=1)
        fm = 0.5 * (sum_sq - sq_sum).sum(dim=1, keepdim=True)
        # 一阶线性:稀疏一维 embedding 之和 + 稠密线性 + 偏置
        lin = self.dense_linear(dense) + self.bias
        for j in range(self.n_fields):
            lin = lin + self.lin_emb[j](sparse[:, j])
        # DNN:拼接稀疏 embedding(展平)与稠密
        dnn_in = torch.cat([embeds.flatten(start_dim=1), dense], dim=1)
        dnn = self.dnn(dnn_in)
        return torch.sigmoid(lin + fm + dnn)


def export_onnx(model, vocab, cardinalities, sid_map=None):
    os.makedirs(MODEL_DIR, exist_ok=True)
    model.eval()
    dummy_dense = torch.zeros(2, len(DENSE_COLS), dtype=torch.float32)
    dummy_sparse = torch.zeros(2, len(SPARSE_ORDER), dtype=torch.int64)
    torch.onnx.export(
        model,
        (dummy_dense, dummy_sparse),
        MODEL_PATH,
        input_names=["dense", "sparse"],
        output_names=["ctr"],
        dynamic_axes={"dense": {0: "N"}, "sparse": {0: "N"}, "ctr": {0: "N"}},
        opset_version=17,
    )
    # 新版 torch 导出器默认把权重外置成 model_deepfm.onnx.data;但 Java 侧从 classpath
    # 以 byte[] 加载,onnxruntime 无法解析外置数据文件 → 必须合并成单一自包含 .onnx。
    import onnx
    consolidated = onnx.load(MODEL_PATH)  # 此刻 .data 还在,可正常读入
    # Java 侧 onnxruntime 仅支持到 IR version 9,torch 新导出器默认是 IR v10 → 必须降到 9
    # (opset 17 在 IR 9 下仍受支持;我们用到的算子都不需要 IR 10)。
    consolidated.ir_version = 9
    onnx.save_model(consolidated, MODEL_PATH, save_as_external_data=False)
    data_file = MODEL_PATH + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)
    schema = {
        "model": "deepfm",
        "dense_order": DENSE_COLS,
        "sparse_order": SPARSE_ORDER,
        "user_buckets": USER_BUCKETS,
        "item_buckets": ITEM_BUCKETS,
        "embed_dim": EMBED_DIM,
        "sparse_cardinalities": cardinalities,
        "semantic_id": sid_map is not None,
    }
    if sid_map is not None:
        # 在线 SparseFeatureEncoder 据此追加 c0,c1,c2;map 从 classpath 读(itemId→[c0,c1,c2])
        schema["semantic_id_map"] = "classpath:model/semantic_id_map.json"
        with open(SEMANTIC_ID_MAP_PATH, "w") as f:
            json.dump({str(k): v for k, v in sid_map.items()}, f)
    with open(SCHEMA_PATH, "w") as f:
        json.dump(schema, f, ensure_ascii=False, indent=2)
    with open(VOCAB_PATH, "w") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)
    verify()


def verify():
    """onnxruntime 回读:确认双输入可加载、输出形状为 [N,1](Java 侧一致性自检)。"""
    try:
        import onnxruntime as ort
    except Exception:
        print("(未装 onnxruntime,跳过回读校验)")
        return
    sess = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
    ins = {i.name: i.shape for i in sess.get_inputs()}
    d = np.zeros((3, len(DENSE_COLS)), dtype="float32")
    s = np.zeros((3, len(SPARSE_ORDER)), dtype="int64")
    out = sess.run(None, {"dense": d, "sparse": s})
    print(f"onnxruntime 回读 OK:输入={ins},输出名={[o.name for o in sess.get_outputs()]},"
          f"输出形状={out[0].shape}")


if __name__ == "__main__":
    main()
