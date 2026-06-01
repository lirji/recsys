#!/usr/bin/env python3
"""Track D · LightGBM CTR 训练 → ONNX 导出。

输入:同目录 samples.csv(由 Java 作业 gen-samples 生成,表头 label,<特征...>,split)。
流程:按 split 切分 train/valid → LightGBM 训练 → 报 AUC/LogLoss
      → onnxmltools 导出 model.onnx(zipmap=False,输出概率张量) + feature_order.json。
产物落到 recsys-rank/src/main/resources/model/,供在线 OnnxRankService 加载。

关键:特征列顺序 = CSV 表头顺序 = Java FeatureAssembler.FEATURE_ORDER,三处必须一致。

用法:
    pip install -r requirements.txt
    python train_lgbm.py
"""
import json
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "samples.csv")
MODEL_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "recsys-rank", "src", "main", "resources", "model"))
MODEL_PATH = os.path.join(MODEL_DIR, "model.onnx")
FEATURE_ORDER_PATH = os.path.join(MODEL_DIR, "feature_order.json")


def main():
    if not os.path.exists(SAMPLES):
        sys.exit(f"找不到 {SAMPLES};请先跑 Java 作业:--job=build-features 再 --job=gen-samples")

    df = pd.read_csv(SAMPLES)
    feature_cols = [c for c in df.columns if c not in ("label", "split")]
    print(f"样本 {len(df)} 行,特征 {len(feature_cols)}: {feature_cols}")
    print(df["label"].value_counts().to_dict(), "| split:", df["split"].value_counts().to_dict())

    train = df[df["split"] == "train"]
    valid = df[df["split"] == "valid"]
    if len(valid) == 0:  # 兜底:无 valid 则随机留出
        train = df.sample(frac=0.8, random_state=42)
        valid = df.drop(train.index)

    x_tr, y_tr = train[feature_cols].astype("float32"), train["label"].astype(int)
    x_va, y_va = valid[feature_cols].astype("float32"), valid["label"].astype(int)

    import lightgbm as lgb
    from sklearn.metrics import roc_auc_score, log_loss

    model = lgb.LGBMClassifier(
        n_estimators=200,
        learning_rate=0.05,
        num_leaves=31,
        max_depth=-1,
        subsample=0.8,
        colsample_bytree=0.8,
        min_child_samples=50,
        random_state=42,
    )
    model.fit(
        x_tr, y_tr,
        eval_set=[(x_va, y_va)],
        eval_metric="auc",
        callbacks=[lgb.early_stopping(30, verbose=False), lgb.log_evaluation(0)],
    )

    p_va = model.predict_proba(x_va)[:, 1]
    auc = roc_auc_score(y_va, p_va)
    ll = log_loss(y_va, p_va, labels=[0, 1])
    print(f"\n==== 评估(valid {len(valid)} 行)====")
    print(f"AUC     = {auc:.4f}")
    print(f"LogLoss = {ll:.4f}")
    imp = sorted(zip(feature_cols, model.feature_importances_), key=lambda t: -t[1])
    print("特征重要度:", imp)

    export_onnx(model, feature_cols)
    print(f"\n✅ 导出完成:\n  {MODEL_PATH}\n  {FEATURE_ORDER_PATH}")


def export_onnx(model, feature_cols):
    from onnxmltools.convert import convert_lightgbm
    try:
        from onnxmltools.convert.common.data_types import FloatTensorType
    except Exception:  # 版本差异
        from skl2onnx.common.data_types import FloatTensorType

    initial_types = [("input", FloatTensorType([None, len(feature_cols)]))]
    # zipmap=False:概率输出为 [N,2] 张量而非 ZipMap(字典序列),Java 侧好取正类列
    onnx_model = convert_lightgbm(
        model, initial_types=initial_types, zipmap=False, target_opset=15
    )

    os.makedirs(MODEL_DIR, exist_ok=True)
    with open(MODEL_PATH, "wb") as f:
        f.write(onnx_model.SerializeToString())
    with open(FEATURE_ORDER_PATH, "w") as f:
        json.dump(feature_cols, f, ensure_ascii=False, indent=2)

    verify(feature_cols)


def verify(feature_cols):
    """用 onnxruntime 回读模型,确认可加载且输出形状合理(与 Java 侧一致性自检)。"""
    try:
        import onnxruntime as ort
    except Exception:
        print("(未装 onnxruntime,跳过回读校验)")
        return
    sess = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    x = np.zeros((2, len(feature_cols)), dtype="float32")
    outs = sess.run(None, {in_name: x})
    shapes = [getattr(o, "shape", type(o).__name__) for o in outs]
    print(f"onnxruntime 回读 OK:输入名={in_name},输出={[o.name for o in sess.get_outputs()]},形状={shapes}")


if __name__ == "__main__":
    main()
