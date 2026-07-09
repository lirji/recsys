#!/usr/bin/env python3
"""双塔召回 · Two-Tower / DSSM 训练 → 离线 item 向量 + 在线 user 塔 ONNX(PyTorch)。

与内容向量召回(Gemini 文本 embedding,学"内容相似")互补:双塔从用户-物品正反馈对里
学"行为相似"(协同信号)。是一个纯 ID 的两塔检索模型:

  - user 塔:userId(取模分桶)→ Embedding → MLP → 64 维 L2 归一化向量;
  - item 塔:itemId(连续 vocab)+ category(vocab)→ Embedding → MLP → 64 维 L2 归一化向量。
  训练:in-batch sampled-softmax —— 一个 batch 内,每个 (user,item) 正样本对,
        把同 batch 其他物品当负例,最大化 user·item 对角相似度(对比学习)。

输入:同目录 samples.csv(gen-samples 产出);只用 label / user_id / item_id / category 四列,
      因此 as-of 与 leaky 两种特征模式都能用(双塔不吃稠密特征)。只用 label==1 的正样本对。

产出(两端一致性契约):
  1. item 向量 → item_tower.csv(item_id,v0..v63),由 Java 作业 import-tower 灌进 item_tower_embedding;
  2. user 塔 → recsys-recall/src/main/resources/model/user_tower.onnx
     (输入 user_bucket[N,1] int64 → user_vec[N,64]);
  3. tower_schema.json(user_buckets / dim / 输入名)。
     在线 TwoTowerRecaller 只读 user_tower.onnx + user_buckets:user_bucket = floorMod(userId, user_buckets)
     与此处取模逐位一致;item 向量已烘焙进库,故在线无需 item / category vocab。

用法:
    /opt/homebrew/bin/python3.11 -m venv .venv && .venv/bin/pip install -r requirements-twotower.txt
    .venv/bin/python train_two_tower.py
    # 然后:mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=import-tower
    #       mvn -pl recsys-recall clean install   # 把 user_tower.onnx 打进 jar
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
SAMPLES = os.path.join(HERE, "samples.csv")
ITEM_CSV = os.path.join(HERE, "item_tower.csv")
MODEL_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "recsys-recall", "src", "main", "resources", "model"))
MODEL_PATH = os.path.join(MODEL_DIR, "user_tower.onnx")
SCHEMA_PATH = os.path.join(MODEL_DIR, "tower_schema.json")

# 一致性契约:user 桶数(在线 floorMod 同款)。userId 1..610 < 5000,故近乎 per-user embedding。
USER_BUCKETS = 5000
DIM = 64           # 输出向量维度(= item_tower_embedding vector(64))
ID_EMBED = 32      # id embedding 维度
CAT_EMBED = 16     # 类目 embedding 维度
TEMP = 0.07        # softmax 温度
INPUT_NAME = "user_bucket"


def main():
    if not os.path.exists(SAMPLES):
        sys.exit(f"找不到 {SAMPLES};请先跑 Java 作业 --job=gen-samples")

    df = pd.read_csv(SAMPLES)
    for c in ["label", "user_id", "item_id", "category"]:
        if c not in df.columns:
            sys.exit(f"samples.csv 缺列 {c};请用最新 gen-samples 重新生成")

    # item vocab:覆盖 samples 里出现过的全部物品(正负样本并集),保证多数候选可被检索到
    item_ids = sorted(df["item_id"].astype("int64").unique().tolist())
    item2idx = {iid: i for i, iid in enumerate(item_ids)}
    n_items = len(item_ids)
    # 每个物品的类目(取任意一行;同一物品类目固定)
    cat_of_item = df.drop_duplicates("item_id").set_index("item_id")["category"].to_dict()
    cats = sorted({c for c in cat_of_item.values() if isinstance(c, str) and c != ""})
    cat_vocab = {c: i + 1 for i, c in enumerate(cats)}   # 0 = OOV/缺失
    cat_card = len(cat_vocab) + 1
    item_cat_idx = np.array(
        [cat_vocab.get(cat_of_item.get(iid), 0) for iid in item_ids], dtype="int64")

    pos = df[df["label"] == 1]
    pos_user = (pos["user_id"].astype("int64") % USER_BUCKETS).to_numpy()
    pos_item = pos["item_id"].astype("int64").map(item2idx).to_numpy()
    n_pos = len(pos_user)
    print(f"样本 {len(df)} 行,正样本对 {n_pos};物品 vocab {n_items},类目 vocab {len(cat_vocab)}(+OOV)")
    if n_pos == 0:
        sys.exit("无正样本(label==1);先跑 gen-samples")

    torch.manual_seed(42)
    model = TwoTower(USER_BUCKETS, n_items, cat_card)
    item_cat_t = torch.from_numpy(item_cat_idx)         # [n_items] 每物品类目索引
    u_t = torch.from_numpy(pos_user)
    i_t = torch.from_numpy(pos_item)
    # in-batch sampled-softmax 的 logQ 修正(Google 2019「Sampling-Bias-Corrected」):热门物品在 batch 里
    # 更常被当负例,不修正会被系统性过度惩罚(假负偏置)。对 logit 减 log P(item),P 用正样本物品频率估。
    # --no-logq 关闭(回到旧行为,便于对比)。
    use_logq = "--no-logq" not in sys.argv
    item_counts = np.bincount(pos_item, minlength=n_items).astype("float64")
    log_q = torch.from_numpy(np.log(item_counts / max(1.0, item_counts.sum()) + 1e-12)).float()
    print(f"logQ 修正:{'开' if use_logq else '关'}")

    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-6)
    batch = 512
    for epoch in range(1, 16):
        model.train()
        perm = torch.randperm(n_pos)
        total, correct, seen = 0.0, 0, 0
        for s in range(0, n_pos, batch):
            idx = perm[s:s + batch]
            if len(idx) < 2:
                continue
            ub = u_t[idx]
            it = i_t[idx]
            uc = model.user_vec(ub)                      # [B, DIM]
            ic = model.item_vec(it, item_cat_t[it])      # [B, DIM]
            logits = uc @ ic.t() / TEMP                  # [B, B] 对角为正例
            if use_logq:
                logits = logits - log_q[it].unsqueeze(0)  # 减 log P(item_j):校正 in-batch 假负偏置
            target = torch.arange(len(idx))
            loss = F.cross_entropy(logits, target)
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
            correct += (logits.argmax(dim=1) == target).sum().item()
            seen += len(idx)
        print(f"epoch {epoch:2d} | loss {total / seen:.4f} | in-batch top1 acc {correct / seen:.3f}")

    # 1) 导出全部 item 向量 → item_tower.csv
    model.eval()
    with torch.no_grad():
        all_idx = torch.arange(n_items)
        item_vecs = model.item_vec(all_idx, item_cat_t).numpy()   # [n_items, DIM] 已 L2 归一化
    with open(ITEM_CSV, "w") as f:
        for iid, vec in zip(item_ids, item_vecs):
            f.write(str(int(iid)) + "," + ",".join(f"{x:.6f}" for x in vec) + "\n")
    print(f"\n✅ item 向量 {n_items} 条 → {ITEM_CSV}")

    # 2) 导出 user 塔 ONNX + schema
    export_user_tower(model)
    print(f"✅ user 塔 → {MODEL_PATH}\n✅ schema → {SCHEMA_PATH}")


class TwoTower(nn.Module):
    """双塔:user 塔(userId)与 item 塔(itemId + category),各自 MLP → DIM,L2 归一化。"""

    def __init__(self, user_buckets, n_items, cat_card):
        super().__init__()
        self.user_emb = nn.Embedding(user_buckets, ID_EMBED)
        self.item_emb = nn.Embedding(n_items, ID_EMBED)
        self.cat_emb = nn.Embedding(cat_card, CAT_EMBED)
        self.user_mlp = nn.Sequential(nn.Linear(ID_EMBED, 128), nn.ReLU(), nn.Linear(128, DIM))
        self.item_mlp = nn.Sequential(nn.Linear(ID_EMBED + CAT_EMBED, 128), nn.ReLU(), nn.Linear(128, DIM))
        nn.init.normal_(self.user_emb.weight, std=0.05)
        nn.init.normal_(self.item_emb.weight, std=0.05)
        nn.init.normal_(self.cat_emb.weight, std=0.05)

    def user_vec(self, user_bucket):
        return F.normalize(self.user_mlp(self.user_emb(user_bucket)), dim=-1)

    def item_vec(self, item_idx, cat_idx):
        x = torch.cat([self.item_emb(item_idx), self.cat_emb(cat_idx)], dim=-1)
        return F.normalize(self.item_mlp(x), dim=-1)


class UserTowerExport(nn.Module):
    """ONNX 导出包装:输入 user_bucket[N,1] int64 → user_vec[N,DIM]。"""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, user_bucket):
        return self.model.user_vec(user_bucket.squeeze(1))


def export_user_tower(model):
    os.makedirs(MODEL_DIR, exist_ok=True)
    wrapper = UserTowerExport(model).eval()
    dummy = torch.zeros(2, 1, dtype=torch.int64)
    torch.onnx.export(
        wrapper, (dummy,), MODEL_PATH,
        input_names=[INPUT_NAME], output_names=["user_vec"],
        dynamic_axes={INPUT_NAME: {0: "N"}, "user_vec": {0: "N"}},
        opset_version=17,
    )
    # 同 DeepFM 两个坑:① 合并外置权重成单一自包含 .onnx(Java 从 classpath 以 byte[] 加载);
    #                  ② Java onnxruntime 仅支持到 IR v9,torch 新导出器默认 IR v10 → 降到 9。
    import onnx
    m = onnx.load(MODEL_PATH)
    m.ir_version = 9
    onnx.save_model(m, MODEL_PATH, save_as_external_data=False)
    data_file = MODEL_PATH + ".data"
    if os.path.exists(data_file):
        os.remove(data_file)
    with open(SCHEMA_PATH, "w") as f:
        json.dump({
            "model": "two_tower",
            "user_buckets": USER_BUCKETS,
            "dim": DIM,
            "input_name": INPUT_NAME,
        }, f, ensure_ascii=False, indent=2)
    verify()


def verify():
    """onnxruntime 回读:确认 user 塔可加载、输出 [N,DIM](Java 一致性自检)。"""
    try:
        import onnxruntime as ort
    except Exception:
        print("(未装 onnxruntime,跳过回读校验)")
        return
    sess = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
    out = sess.run(None, {INPUT_NAME: np.array([[1], [2], [3]], dtype="int64")})
    print(f"onnxruntime 回读 OK:输入名={[i.name for i in sess.get_inputs()]},"
          f"输出形状={out[0].shape}(应为 (3,{DIM}))")


if __name__ == "__main__":
    main()
