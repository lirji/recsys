#!/usr/bin/env python3
"""深度排序 · DCN v2 CTR 训练 → ONNX 导出(PyTorch,R5)。

DCN v2(Deep & Cross Network v2)= Cross Network(**矩阵形式**的显式高阶特征交叉)
并联 Deep MLP,拼接后过输出层出 CTR。相对 DeepFM 的 FM 二阶交叉,DCN 的 Cross Network
在每一层显式做一次特征交叉,L 层堆叠得到到 (L+1) 阶的有界多项式交叉——这是它相对
DeepFM 的净增量(显式、可控阶数的高阶交叉)。

    Cross 层(v2 矩阵形式):x_{l+1} = x0 ⊙ (W_l · x_l + b_l) + x_l
    (v1 是向量形式 x0·(x_l·w_l)+b+x_l;v2 用满秩 W_l 表达更强,故名 "v2")

输入:同目录 samples.csv(由 Java 作业 gen-samples 生成,与 train_deepfm.py 共用),表头:
      label, user_id, item_id, category, <5 稠密特征>, split

跨语言编码契约(必须与 Java SparseFeatureEncoder 逐位一致,常量与 DeepFM 同源):
  - user_bucket = user_id % USER_BUCKETS
  - item_bucket = item_id % ITEM_BUCKETS
  - cat_idx     = category_vocab[category](0 = 未登录/缺失)
导出三件套到 recsys-rank/src/main/resources/model/:
  model_dcn.onnx(双输入 dense[N,5] float32 + sparse[N,3] int64,输出 ctr[N,1])、
  dcn_schema.json(桶大小/字段顺序/基数)、dcn_category_vocab.json(类目→索引)。
在线契约与 DeepFM 完全一致 → DcnRankService 是 DeepFmRankService 的镜像,只换文件路径。

用法:
    pip install -r requirements-deepfm.txt   # 依赖与 DeepFM 相同(torch + onnx + onnxruntime)
    python train_dcn.py
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
MODEL_PATH = os.path.join(MODEL_DIR, "model_dcn.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "dcn_schema.json")
VOCAB_PATH = os.path.join(MODEL_DIR, "dcn_category_vocab.json")
SEMANTIC_ID_CSV = os.path.join(HERE, "item_semantic_id.csv")               # train_rqvae.py 产出
SEMANTIC_ID_MAP_PATH = os.path.join(MODEL_DIR, "dcn_semantic_id_map.json")  # 在线 SparseFeatureEncoder 读

# 稀疏编码契约(改这里必须同步 Java SparseFeatureEncoder,且与 train_deepfm.py 一致)
USER_BUCKETS = 5000
ITEM_BUCKETS = 20000
EMBED_DIM = 16
# Cross Network 层数(=最高显式交叉阶数;3 层 → 到 4 阶交叉)
N_CROSS = 3
# Deep 塔隐藏层
DEEP_HIDDEN = (128, 64)
# 语义 ID 稀疏特征(可选,--semantic-id 开启):把 RQ-VAE 生成的 (c0,c1,c2) 当稀疏字段进精排。
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
# S2 特征扩充:--extended-features 时 dense 用 8 维(需 gen-samples --extended-features 产出对应列)。
EXTENDED_DENSE = ["user_cat_cnt_norm", "user_cat_ratio", "item_rating_std"]
USE_EXTENDED = "--extended-features" in sys.argv


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
    if USE_EXTENDED:
        DENSE_COLS.extend(EXTENDED_DENSE)
        print(f"S2 特征扩充:启用,dense_order={DENSE_COLS}")
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
    # embedding 是随机初始噪声)。故这里用随机切分(与 train_deepfm.py 同口径),而非 CSV 的时间切分。
    # 因此 DCN 的 valid AUC 不能与 LightGBM 的时间切分 AUC 直接比——公平比较请用 eval 作业。
    rng = np.random.default_rng(42)
    is_train = rng.random(len(df)) < 0.85

    from sklearn.metrics import roc_auc_score, log_loss

    torch.manual_seed(42)
    device = torch.device("cpu")  # 数据集小,CPU 训练即可;导出也在 CPU

    model = DCNv2(cardinalities, n_dense=len(DENSE_COLS), embed_dim=EMBED_DIM).to(device)

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


class DCNv2(nn.Module):
    """DCN v2:Cross Network(矩阵形式显式高阶交叉)并联 Deep MLP,拼接后出 CTR。

    输入向量 x0 = concat(各稀疏字段 embedding 展平, 稠密特征);Cross 与 Deep 均以 x0 为输入,
    Cross 输出保持 input_dim 维、Deep 输出末层隐藏维,拼接后线性到 1 维再 sigmoid。
    """

    def __init__(self, cardinalities, n_dense, embed_dim, n_cross=N_CROSS, deep_hidden=DEEP_HIDDEN):
        super().__init__()
        self.n_fields = len(cardinalities)
        self.embed_dim = embed_dim
        self.emb = nn.ModuleList([nn.Embedding(c, embed_dim) for c in cardinalities])
        self.input_dim = self.n_fields * embed_dim + n_dense
        # Cross Network(v2):每层一个 input_dim×input_dim 满秩权重 + 偏置(nn.Linear 即 W·x+b)
        self.cross = nn.ModuleList([nn.Linear(self.input_dim, self.input_dim) for _ in range(n_cross)])
        # Deep Network
        deep, prev = [], self.input_dim
        for h in deep_hidden:
            deep += [nn.Linear(prev, h), nn.ReLU(), nn.Dropout(0.2)]
            prev = h
        self.deep = nn.Sequential(*deep)
        self.deep_out_dim = prev
        # 并联:拼 cross_out(input_dim) + deep_out(deep_out_dim) → 输出层
        self.out = nn.Linear(self.input_dim + self.deep_out_dim, 1)
        for e in self.emb:
            nn.init.normal_(e.weight, std=0.01)

    def forward(self, dense, sparse):
        embeds = [self.emb[j](sparse[:, j]) for j in range(self.n_fields)]
        x0 = torch.cat(embeds + [dense], dim=1)   # [N, input_dim]
        # Cross Network:x_{l+1} = x0 ⊙ (W_l x_l + b_l) + x_l
        xl = x0
        for layer in self.cross:
            xl = x0 * layer(xl) + xl
        # Deep Network(以 x0 为输入)
        dl = self.deep(x0)
        # 并联拼接 → 输出层 → sigmoid
        return torch.sigmoid(self.out(torch.cat([xl, dl], dim=1)))


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
    # 新版 torch 导出器默认把权重外置成 model_dcn.onnx.data;但 Java 侧从 classpath
    # 以 byte[] 加载,onnxruntime 无法解析外置数据文件 → 必须合并成单一自包含 .onnx。
    import onnx
    consolidated = onnx.load(MODEL_PATH)  # 此刻 .data 还在,可正常读入
    # Java 侧 onnxruntime 仅支持到 IR version 9,torch 新导出器默认是 IR v10 → 必须降到 9。
    consolidated.ir_version = 9
    onnx.save_model(consolidated, MODEL_PATH, save_as_external_data=False)
    data_file = MODEL_PATH + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)
    schema = {
        "model": "dcn",
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
        schema["semantic_id_map"] = "classpath:model/dcn_semantic_id_map.json"
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
