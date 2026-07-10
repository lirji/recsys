#!/usr/bin/env python3
"""SIM 长序列训练 → model_sim.onnx(R3)。

SIM 的 ESU(Exact Search Unit)与 DIN 同架构,故本脚本直接<b>复用 train_din.py 的模型/训练/导出</b>,
只把输出改到 sim_*。SIM 相对 DIN 的核心差异在<b>在线</b>:GSU 按候选类目从用户长历史硬检索出相关子序列
(见 Java 侧 SimGsu / SimRankService),把有效历史拉长一个量级。

<b>已知简化</b>:训练侧理想上也应喂 GSU 检索序列(类目过滤的长历史);此脚手架先复用 gen-samples-mt 的
近 20 序列训 ESU —— train/serve 序列分布略有偏差,但仍是有效的 ESU 模型。要严格对齐可扩 gen-samples
产出"按类目过滤的长历史序列"再训。

用法(需 recsys-offline/train/.venv + samples_mt.csv):
    python train_sim.py
    python train_sim.py --extended-features
"""
import os

import train_din as T

# 复用 DIN 的一切,只改输出文件名 → model_sim.onnx / sim_schema.json / sim_category_vocab.json
T.MODEL_PATH = os.path.join(T.MODEL_DIR, "model_sim.onnx")
T.SCHEMA_PATH = os.path.join(T.MODEL_DIR, "sim_schema.json")
T.VOCAB_PATH = os.path.join(T.MODEL_DIR, "sim_category_vocab.json")

if __name__ == "__main__":
    print("SIM 训练:复用 DIN ESU 架构 → model_sim.onnx / sim_schema.json / sim_category_vocab.json")
    T.main()
