#!/usr/bin/env python3
"""延迟反馈 CVR · Chapelle 2014 DFM 训练 → ONNX 导出(PyTorch,A6)。

**解决什么**:广告 pCVR 现复用推荐 MMoE 的 cvr 头,但转化有延迟——训练时"已点击未转化"被当硬负例,
即使日后会转化 → 系统性低估 pCVR(删失偏置)。本训练器做 Chapelle 2014 带删失**联合**估计:
  - CVR 头  p(x) = sigmoid(w_c·body(x))         —— 该点击**终将转化**的概率(= 去偏 pCVR,导出此头)
  - 延迟头 λ(x) = softplus(w_d·body(x)) + ε      —— 指数延迟率(仅训练,不导出;类比 PAL 位置塔)

删失似然(每条**点击**样本,观测于点击后 elapsed=e;转化则观测延迟 d):
  - converted=1: p·λ·e^(−λd)              → NLL = −log p − log λ + λd
  - converted=0: (1−p) + p·e^(−λe)        → NLL = −log((1−p) + p·e^(−λe))
关键去偏:e 小(刚点击)时 e^(−λe)→1 ⇒ 负例几乎不罚(还判断不了);e 大才逐渐当真负例。

输入:同目录 ad_cvr_samples.csv(由 Java 作业 gen-ad-cvr-samples 生成),表头:
      converted, delay_days, elapsed_days, user_id, item_id, category, <5 稠密特征>, split

在线契约与 DeepFM 完全一致(复用 recsys-ad DfmCvrService = DeepFmRankService 镜像):双输入
dense[N,5] float32 + sparse[N,3] int64、**单输出 pcvr[N,1]**、同一 SparseFeatureEncoder 编码。
导出到 recsys-ad/src/main/resources/model/:model_dfm_cvr.onnx + dfm_cvr_schema.json + dfm_cvr_category_vocab.json。

用法:
    pip install -r requirements-deepfm.txt   # 依赖同 DeepFM(torch + onnx + onnxruntime)
    python train_dfm.py
"""
import json
import os
import sys

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "ad_cvr_samples.csv")
# 注意:导出到 recsys-ad(不是 recsys-rank)——A6 广告 CVR 模型归属广告库
MODEL_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "recsys-ad", "src", "main", "resources", "model"))
MODEL_PATH = os.path.join(MODEL_DIR, "model_dfm_cvr.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "dfm_cvr_schema.json")
VOCAB_PATH = os.path.join(MODEL_DIR, "dfm_cvr_category_vocab.json")

# 稀疏编码契约(与 Java SparseFeatureEncoder / train_deepfm.py 逐位一致)
USER_BUCKETS = 5000
ITEM_BUCKETS = 20000
EMBED_DIM = 16

# 稠密特征列顺序(= Java FeatureAssembler.FEATURE_ORDER)
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
        sys.exit(f"找不到 {SAMPLES};请先跑 Java 作业:--job=sim-ad-events 再 --job=gen-ad-cvr-samples")

    df = pd.read_csv(SAMPLES)
    need = DENSE_COLS + SPARSE_ORDER + ["converted", "delay_days", "elapsed_days"]
    missing = [c for c in need if c not in df.columns]
    if missing:
        sys.exit(f"ad_cvr_samples.csv 缺列 {missing};请用最新 gen-ad-cvr-samples 重新生成")

    converted = df["converted"].to_numpy().astype("float32")
    naive_rate = float(converted.mean())
    print(f"样本 {len(df)} 行 | 观测转化率(朴素,被删失低估)= {naive_rate:.4f} "
          f"| 转化 {int(converted.sum())} / 点击 {len(df)}")

    vocab = build_vocab(df["category"])
    cat_card = len(vocab) + 1
    cardinalities = [USER_BUCKETS, ITEM_BUCKETS, cat_card]
    print(f"类目 vocab 大小 {len(vocab)}(+OOV);稀疏基数 = {cardinalities}")

    sparse = encode_sparse(df, vocab)
    dense = df[DENSE_COLS].to_numpy().astype("float32")
    delay = df["delay_days"].to_numpy().astype("float32")
    delay = np.clip(delay, 0.0, None)   # 未转化行 delay 可能是 -1,不参与转化项(乘 converted 掩掉)
    elapsed = np.clip(df["elapsed_days"].to_numpy().astype("float32"), 0.0, None)

    rng = np.random.default_rng(42)
    is_train = rng.random(len(df)) < 0.85

    torch.manual_seed(42)
    device = torch.device("cpu")
    model = Dfm(cardinalities, n_dense=len(DENSE_COLS), embed_dim=EMBED_DIM).to(device)

    x_d = torch.from_numpy(dense)
    x_s = torch.from_numpy(sparse)
    y_c = torch.from_numpy(converted)
    y_d = torch.from_numpy(delay)
    y_e = torch.from_numpy(elapsed)
    tr = torch.from_numpy(np.where(is_train)[0])
    va = torch.from_numpy(np.where(~is_train)[0])

    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-5)
    batch = 1024
    best_loss, best_state, patience, bad = float("inf"), None, 4, 0

    for epoch in range(1, 41):
        model.train()
        perm = tr[torch.randperm(len(tr))]
        total = 0.0
        for i in range(0, len(perm), batch):
            idx = perm[i:i + batch]
            opt.zero_grad()
            cvr_logit, delay_log = model(x_d[idx], x_s[idx])
            loss = dfm_nll(cvr_logit, delay_log, y_c[idx], y_d[idx], y_e[idx])
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        model.eval()
        with torch.no_grad():
            vcl, vdl = model(x_d[va], x_s[va])
            vloss = dfm_nll(vcl, vdl, y_c[va], y_d[va], y_e[va]).item()
        print(f"epoch {epoch:2d} | train_nll {total / len(tr):.4f} | valid_nll {vloss:.4f}")
        if vloss < best_loss - 1e-5:
            best_loss, bad = vloss, 0
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
        else:
            bad += 1
            if bad >= patience:
                print(f"早停(valid_nll {patience} 轮未改善)")
                break

    if best_state is not None:
        model.load_state_dict(best_state)
    model.eval()
    with torch.no_grad():
        cl, _ = model(x_d, x_s)
        p_hat = torch.sigmoid(cl).squeeze(1).numpy()
    mean_phat = float(p_hat.mean())
    print(f"\n==== 去偏自检 ====\n朴素观测转化率 = {naive_rate:.4f}(被延迟删失低估)"
          f"\nDFM 恢复 pCVR 均值 = {mean_phat:.4f}(应 > 朴素;= 终值转化概率)")

    export_onnx(model, vocab, cardinalities)
    print(f"\n✅ 导出完成:\n  {MODEL_PATH}\n  {SCHEMA_PATH}\n  {VOCAB_PATH}")


def dfm_nll(cvr_logit, delay_log, converted, delay, elapsed):
    """Chapelle 删失负对数似然(逐样本按 converted 分支,均值聚合)。"""
    cl = cvr_logit.squeeze(1)
    logp = F.logsigmoid(cl)          # log p
    log1mp = F.logsigmoid(-cl)       # log(1 - p)
    p = torch.sigmoid(cl)
    lam = F.softplus(delay_log.squeeze(1)) + 1e-4    # 延迟率 > 0
    # converted=1: −log p − log λ + λ·delay
    loss_conv = -(logp + torch.log(lam) - lam * delay)
    # converted=0: −log((1−p) + p·e^(−λ·elapsed))
    term = torch.exp(log1mp) + p * torch.exp(-lam * elapsed)
    loss_cens = -torch.log(term.clamp_min(1e-12))
    return (converted * loss_conv + (1.0 - converted) * loss_cens).mean()


class Dfm(nn.Module):
    """共享 embedding + DNN body,两线性头:cvr_logit、delay_log_rate。"""

    def __init__(self, cardinalities, n_dense, embed_dim):
        super().__init__()
        self.n_fields = len(cardinalities)
        self.emb = nn.ModuleList([nn.Embedding(c, embed_dim) for c in cardinalities])
        input_dim = self.n_fields * embed_dim + n_dense
        self.body = nn.Sequential(
            nn.Linear(input_dim, 128), nn.ReLU(), nn.Dropout(0.2),
            nn.Linear(128, 64), nn.ReLU(),
        )
        self.cvr_head = nn.Linear(64, 1)
        self.delay_head = nn.Linear(64, 1)
        for e in self.emb:
            nn.init.normal_(e.weight, std=0.01)

    def forward(self, dense, sparse):
        embeds = [self.emb[j](sparse[:, j]) for j in range(self.n_fields)]
        x = torch.cat(embeds + [dense], dim=1)
        h = self.body(x)
        return self.cvr_head(h), self.delay_head(h)


class CvrHead(nn.Module):
    """导出包装:只出去偏 pCVR = sigmoid(cvr_logit)(延迟头不入图)。"""

    def __init__(self, dfm):
        super().__init__()
        self.dfm = dfm

    def forward(self, dense, sparse):
        cvr_logit, _ = self.dfm(dense, sparse)
        return torch.sigmoid(cvr_logit)


def export_onnx(model, vocab, cardinalities):
    os.makedirs(MODEL_DIR, exist_ok=True)
    head = CvrHead(model)
    head.eval()
    dummy_dense = torch.zeros(2, len(DENSE_COLS), dtype=torch.float32)
    dummy_sparse = torch.zeros(2, len(SPARSE_ORDER), dtype=torch.int64)
    torch.onnx.export(
        head,
        (dummy_dense, dummy_sparse),
        MODEL_PATH,
        input_names=["dense", "sparse"],
        output_names=["pcvr"],
        dynamic_axes={"dense": {0: "N"}, "sparse": {0: "N"}, "pcvr": {0: "N"}},
        opset_version=17,
    )
    # Java onnxruntime 仅支持 IR≤9 且需单文件自包含(从 classpath 以 byte[] 加载)——同 train_deepfm.py
    import onnx
    consolidated = onnx.load(MODEL_PATH)
    consolidated.ir_version = 9
    onnx.save_model(consolidated, MODEL_PATH, save_as_external_data=False)
    data_file = MODEL_PATH + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)
    schema = {
        "model": "dfm_cvr",
        "dense_order": DENSE_COLS,
        "sparse_order": SPARSE_ORDER,
        "user_buckets": USER_BUCKETS,
        "item_buckets": ITEM_BUCKETS,
        "embed_dim": EMBED_DIM,
        "sparse_cardinalities": cardinalities,
        "semantic_id": False,
    }
    with open(SCHEMA_PATH, "w") as f:
        json.dump(schema, f, ensure_ascii=False, indent=2)
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
    ins = {i.name: i.shape for i in sess.get_inputs()}
    d = np.zeros((3, len(DENSE_COLS)), dtype="float32")
    s = np.zeros((3, len(SPARSE_ORDER)), dtype="int64")
    out = sess.run(None, {"dense": d, "sparse": s})
    print(f"onnxruntime 回读 OK:输入={ins},输出名={[o.name for o in sess.get_outputs()]},"
          f"输出形状={out[0].shape}")


if __name__ == "__main__":
    main()
