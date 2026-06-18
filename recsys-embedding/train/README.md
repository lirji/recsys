# 本地 BGE 向量化(离线导出 → Java onnxruntime 推理)

不依赖外部 API 的向量化兜底:`provider=local` 时由 `LocalBgeEmbeddingClient` 用
onnxruntime 跑 BGE 句向量模型,纯 Java `BgeTokenizer`(BERT WordPiece)分词。
用于断网 / Gemini 失效或超额,也是把全量 9742 物品向量灌满的正路(无 API 限额)。

## 一、导出模型(一次性)

```bash
cd recsys-embedding/train
python -m venv .venv && source .venv/bin/activate     # 可选
pip install -r requirements-bge.txt

python export_bge_onnx.py --verify
# → ~/.recsys/models/bge-base-en-v1.5/{model.onnx, vocab.txt}
# --verify 会用 onnxruntime 复算并与 torch 对比余弦(应 ≈ 1.0)
```

模型默认 **`BAAI/bge-base-en-v1.5`(768 维)**——正好对齐全库 `item_embedding vector(768)`
契约,无需改 schema;MovieLens 文本为英文。模型 ≈400MB,**不入 git**(放文件系统)。

> 想用更小的 `bge-small-en-v1.5`(384 维)?需同时把 `recsys.embedding.dimension`、
> `item_embedding`/`user_embedding`/`item_tower_embedding` 的 `vector(...)` 维度都改为 384 并重灌。

## 二、切到 local 并灌向量

```bash
export EMBEDDING_PROVIDER=local
# 若模型不在默认目录,另设(application.yml 的 BGE_MODEL_PATH/BGE_VOCAB_PATH):
# export BGE_MODEL_PATH=/path/model.onnx  BGE_VOCAB_PATH=/path/vocab.txt

mvn -pl recsys-offline spring-boot:run \
  -Dspring-boot.run.arguments="--job=backfill-embedding --skip-existing"
```

注意:换 provider = 换向量空间。BGE 与 Gemini 向量不可混用余弦——首次切 local 建议
**全量重灌**(去掉 `--skip-existing` 或清空 `item_embedding` 后重跑),`item_embedding.model`
列会记为 `bge-base-en-v1.5-local` 便于区分来源。重灌后按需重跑 `user-embedding`。

rec-engine 在线 query 向量化(SEMANTIC 召回 / 搜索)同样吃这套:设 `EMBEDDING_PROVIDER=local` 即可。

## 在线/离线契约

`BgeTokenizer`(Java)必须与导出侧 HF `BertTokenizer` 一致:uncased 小写 + 去重音、
标点独立成词、WordPiece 贪心最长匹配 `##` 子词、`[CLS]...[SEP]`、按 `vocab.txt` 行号取 id。
池化(取 `[CLS]`)+ L2 归一化在 Java 侧做,与 sentence-transformers 默认口径一致。
这与 `SparseFeatureEncoder` / `SequenceEncoder` 是同类契约——改一侧必须同步另一侧。
