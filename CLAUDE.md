# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A production-oriented recommendation-system scaffold: Java + Spring Boot 3.2, Maven multi-module single repo. It implements the classic funnel **recall → rank → rerank**, with an offline/near-line path (embeddings, collaborative filtering, model training) feeding online stores. Built to run fully locally via `docker compose`, but structured to split into microservices later.

**Current state: core funnel implemented & verified locally (M1–M3).** Recall (9 channels: vector/i2i/hot/tag + u2u/swing/semantic/cold + two_tower — the last a DSSM-style learned recall: item tower baked into pgvector offline, user tower run online via ONNX), rank (rule + LightGBM→ONNX + DeepFM + MMoE multi-task/ESMM + DIN sequence), pluggable rerank (diversity/mmr/none), layered A/B experiments (recall×rank×rerank, deterministic bucketing, exposure logged to `user_behavior.bucket`), cold-start (detector + interest onboarding) are all in place. Offline jobs feed the online stores. The design docs in `docs/` are the source of truth — keep them in sync when changing behavior.

## Build & run

```bash
# JDK 21 (pom sets java.version=21)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

mvn clean install                          # build all modules
mvn -pl recsys-common install              # build one module (and its deps with -am)
mvn -pl recsys-rec-engine spring-boot:run  # run a service ([app] modules only)
mvn -pl <module> test                      # test one module
mvn -pl <module> -Dtest=ClassName#method test   # run a single test

docker compose up -d                       # postgres(pgvector) + redis; schema auto-applied on first start
docker compose --profile full up -d        # also start kafka + nacos (optional components)

cp .env.example .env                        # then fill GEMINI_API_KEY etc. (.env is gitignored — never commit)
```

**Offline jobs** are not separate mains — `recsys-offline` is one Spring Boot app whose `JobRunner` dispatches on a `--job=<name>` arg (each job is an `OfflineJob` bean keyed by its `name()`):

```bash
mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=import-items
```

Job names (run roughly in this order to bootstrap the stores): `import-items`, `import-behavior`, `backfill-embedding`, `user-embedding`, `item-cf`, `user-cf`, `swing`, `hot`, `build-features`, `gen-samples`, `gen-samples-mt` (multi-task + behavior-sequence samples → `train/samples_mt.csv`, a **separate** file feeding MMoE/DIN; see below), `import-tower` (loads the two-tower item vectors from `train/item_tower.csv` into `item_tower_embedding`; self-creates the table). Running with no `--job` logs the available set and exits. **Evaluation jobs** (run after the stores are built): `eval` (offline recommendation quality — time-split held-out positives → reuses the online recall→rank pipeline → Precision/Recall/NDCG/MAP/MRR/HitRate/Coverage/Diversity/Novelty @K; supports `--k=10,20,50`, `--recall-only` for per-channel recall, `--rank-strategy=v1|onnx|deepfm`, `--max-users`, `--threads` (parallel per-user evaluation, default = CPU cores); writes `eval/metrics-<ts>.csv`) and `ab-report` (online per-bucket CTR from the `IMPRESSION`/click exposure logs; writes `eval/ab-report-<ts>.csv`).

**Ranking models (`recsys-offline/train/`)** — two trainers feed the online ONNX path, selected by `recsys.rank.strategy` (`RANK_STRATEGY` env), both falling back to rule scoring if the model is absent/fails:
- `train_lgbm.py` → `model.onnx` (LightGBM, `strategy=onnx`): single dense input `[N,5]` (the 5 `FeatureAssembler` features).
- `train_deepfm.py` → `model_deepfm.onnx` + `rank_schema.json` + `category_vocab.json` (PyTorch DeepFM, `strategy=deepfm`): **dual input** `dense[N,5]` float + `sparse[N,3]` int64 (userId/itemId/category embeddings). Online `DeepFmRankService` encodes the sparse ids via `SparseFeatureEncoder` whose bucketing (`floorMod`) + category vocab must match the trainer exactly — that's the online/offline contract for the embeddings, analogous to `FeatureAssembler` for dense. **Both trainers share one `samples.csv`** (gen-samples emits dense + raw `user_id,item_id,category`); `train_lgbm` ignores the raw id columns. **gen-samples computes features as-of (point-in-time) by default** via `AsOfFeatureBuilder` — it streams ratings in ts order and snapshots each sample's features strictly *before* applying that event, so no future/label info leaks into training (no Redis `feat:*` needed). Pass `--leaky` to instead read the full-history `feat:*` aggregates (the old behavior, retained only for eval A/B comparison of "before/after fixing leakage"; requires `build-features` first). DeepFM uses a **random** train/valid split (id-embedding models need every id seen in train), so its AUC is not comparable to LightGBM's time-split AUC — use the `eval` job for an apples-to-apples ranking comparison. DeepFM needs `pip install -r requirements-deepfm.txt` (torch + onnxscript); the exporter is forced to a single self-contained `.onnx` (no external `.data`) because Java loads it from classpath as a byte array. After retraining, **`mvn -pl recsys-rank clean install`** to repack the model into the jar (plain `install` leaves stale resources in `target/classes`).
- `train_mmoe.py` → `model_mmoe.onnx` + `mmoe_schema.json` + `mmoe_category_vocab.json` (PyTorch **MMoE multi-task + ESMM**, `strategy=mmoe`): same dual input as DeepFM but **dual output** `ctr[N,1]` + `cvr[N,1]`. Trains on `samples_mt.csv` (not `samples.csv`) which `gen-samples-mt` emits with **two labels** — `label_click` (interacted at all) and `label_like` (rated ≥4); negatives are popularity-sampled non-interactions. ESMM loss = `BCE(pCTR, click) + BCE(pCTR·pCVR, like)` over the whole space (the CVR tower never directly sees "exposed-not-clicked", removing CVR sample-selection bias). Online `MmoeRankService` reads both heads and fuses `score = pCTR·(cvrBias + cvrWeight·pCVR)` (weights in `recsys.rank.multi-task`, tunable without retrain; `cvrBias=0,cvrWeight=1` ⇒ pCTCVR).
- `train_din.py` → `model_din.onnx` + `din_schema.json` + `din_category_vocab.json` (PyTorch **DIN behavior-sequence + MMoE heads**, `strategy=din`): **four inputs** `dense[N,5]` + `sparse[N,3]` + `seq[N,L]` (item buckets) + `seq_len[N]`, dual output ctr/cvr. The candidate item does target-attention over the user's history (candidate & sequence **share the item embedding table**), pad positions masked via `seq_len` (empty sequence ⇒ pooled vector forced to 0, so cold users don't get garbage). `gen-samples-mt` emits the as-of behavior sequence per sample (recent ≤20 items with rating ≥4, snapshot **before** the current event = point-in-time, no leakage). Online `DinRankService` fetches the user's recent ≤`seqLen` rating-≥4 items **via JdbcTemplate** at request time (queried once per user, broadcast across candidates), encoded by `SequenceEncoder` whose fixed length + right-padding + item bucketing must match `train_din.py` (the online/offline contract for sequences, analogous to `SparseFeatureEncoder`). Both MMoE & DIN: random split, dual-output ONNX, same IR9 / self-contained `.onnx` caveats as DeepFM; both fall back to rule scoring if the model is absent. The `eval` job accepts `--rank-strategy=mmoe|din`. Note the eval ground-truth (rating ≥4 held-out positives) **equals DeepFM's single label**, so DeepFM leads on NDCG/precision while the multi-task models lead on HitRate@20 / coverage / diversity / novelty and on offline AUC — a genuine multi-objective trade-off, not a regression. `run_eval_compare.sh` runs all five strategies on one ground truth.

**Recall model (`train/train_two_tower.py`)** — a pure-ID two-tower / DSSM for *learned* recall (channel `TWO_TOWER`), complementing the content-based `VECTOR` channel. Trains on the positive `(user_id, item_id)` pairs in `samples.csv` (only label/id/category columns, so as-of vs leaky doesn't matter) with in-batch sampled-softmax. Outputs: (1) `item_tower.csv` — every item's 64-d vector (item tower = itemId+category embeddings), loaded into `item_tower_embedding` by the `import-tower` job; (2) `user_tower.onnx` + `tower_schema.json` into **`recsys-recall`'s** resources (not rank's) — the user tower (userId-bucket embedding). Online `TwoTowerRecaller` loads `user_tower.onnx`, computes the query vector for `user_bucket = floorMod(userId, user_buckets)` (same modulo as training; `user_buckets` from the schema), then does pgvector cosine ANN over `item_tower_embedding`. Item vectors are baked in offline, so online needs no item/category vocab. Same ONNX export caveats as DeepFM (IR9, single self-contained file). After retraining, **`mvn -pl recsys-recall clean install`** to repack `user_tower.onnx`. Degrades gracefully: missing model → channel returns empty, other channels cover. Per-channel `eval --recall-only` shows `TWO_TOWER` topping the learned channels (note: trained on the full period incl. test positives, so absolute numbers are optimistic — same caveat as the CF/vector channels).

DB schema lives in `recsys-offline/sql/01_schema.sql` and is auto-executed by the postgres container on first boot (mounted to `docker-entrypoint-initdb.d`). To re-run it, drop the `pgdata` volume.

> Note: there are currently no automated tests in the repo; the `mvn ... test` lines above are the conventions to use when adding them.

## Architecture

**Module layout** — `[app]` = runnable service (port), `[lib]` = computation/domain library (no executable jar):

- `recsys-common` — shared contracts: interfaces, DTOs (records), constants, Redis keys. **The foundation of parallel development; depended on by everything. Changing it ripples everywhere — broadcast before editing.**
- `recsys-rec-engine` [app :8081] — orchestration, the main external entry. `GET /api/recommend`; cold-start interest onboarding via `GET/POST /api/user/{id}/interests`.
- `recsys-gateway` [app :8080] — Spring Cloud Gateway routing.
- `recsys-behavior` [app :8082] — behavior ingestion. `POST /api/behavior` → Kafka or DB.
- `recsys-web` [app :8090] — demo frontend.
- `recsys-offline` [app] — offline jobs: data import, CF batch, embedding backfill, sample generation. `train/` holds Python LightGBM/DeepFM/two-tower → ONNX scripts.
- `recsys-streaming` [app] — Flink real-time feature job (`RealtimeFeatureJob`), runs on a local embedded MiniCluster (no separate Flink cluster). Consumes Kafka `behavior-events` → realtime hot ZSet `recall:rt_hot` + per-user realtime category prefs `rt:user:{id}` (Hash field=category, value=recent count, TTL'd). `HotRecaller` reads `recall:rt_hot` first, falling back to the offline `recall:hot`. `TagRecaller` blends `rt:user:{id}` into the TAG channel — realtime categories are unioned with the static `app_user.profile` categories and weighted by recent count (`weight = 1 + boost·count/maxCount`), so what the user is engaging with *right now* surfaces in TAG even if their profile doesn't have it yet (toggle `recsys.recall.tag.realtime-enabled`, default on; degrades to static-only if Redis is unavailable). Build a fat jar (`mvn -pl recsys-streaming -am package`) and run via `bash recsys-streaming/run-streaming.sh` (the script adds the Java 21 `--add-opens` flags Flink needs). Requires `docker compose --profile full up -d` (Kafka) and behavior running with `BEHAVIOR_USE_KAFKA=true`.
- `recsys-recall` / `recsys-rank` / `recsys-feature` / `recsys-embedding` / `recsys-content` / `recsys-user` [lib] — the recall, ranking, feature, embedding, content, and user-profile libraries.

**Monolith-first, microservices-ready.** `RecEngineApplication` uses `@SpringBootApplication(scanBasePackages = "com.recsys")` so the `[lib]` modules' `@Component`/`@RestController` beans are all wired into one process. Module boundaries are already drawn, so splitting into independent services later is cheap. Keep cross-module coupling to the `recsys-common` interfaces.

**Request flow** (`docs/02-架构设计.md` §6): rec-engine checks `cache:rec:{userId}` → cold-start detection + layered A/B assignment → multi-channel recall (channels gated by the experiment's recall variant / cold-start override) merged & deduped (primary source chosen by priority) → rank (assemble features + ONNX or rule scoring, strategy per rank variant) → fuse recall+rank scores, then multiply by a per-channel boost (`recsys.fusion.channel-boost`, default `TAG: 1.5`, multi-hit takes the max) so interest signals like TAG — which carries the realtime `rt:user` category prefs — aren't drowned by HOT/CF popularity in the global normalization → rerank (strategy per rerank variant; cold-start forces strong diversity) → truncate, build reasons, log exposure with bucket tag, cache, return.

**Online vs offline split** is the core idea: online path is synchronous and millisecond-latency (only "lookup + light scoring"); the offline/near-line path precomputes the heavy work (embeddings, CF inverted index, trained models) and writes results into the online stores (pgvector, Redis).

## Conventions & contracts (`recsys-common`)

- Core interfaces: `RecallService`, `RankService`, `FeatureService`, `EmbeddingClient`. Implementations live in their respective `[lib]` modules.
- DTOs and channel/action types are Java `record`s / enums in `dto/`, `recall/`, `rank/`, `constant/`.
- **All Redis keys go through `RedisKeys`** — no hardcoded key strings scattered across modules.
- **Embedding dimension is fixed at 768** (`recsys.embedding.dimension`, matching `item_embedding vector(768)`). Different models have different dimensions; mixing them corrupts retrieval. Changing the model means a full re-embedding of the whole corpus; track origin via the `model` column.
- Tunable params (recall quotas, rank strategy, rerank limits, cache TTL) live in each `[app]`'s `application.yml` under the `recsys.*` tree, env-var overridable (e.g. `RANK_STRATEGY`, `EMBEDDING_PROVIDER`); designed to migrate to Nacos for hot reload.

## Parallel development model

`PLAN.md` splits Phase 1 into independent Tracks (A: data/embedding, B: recall, C: rank/feature, D: Python training, E: behavior/offline, F: orchestration/gateway/web), each a separate task. The rules:

1. Touch only the modules your Track owns.
2. Depend on the `recsys-common` contracts; do not change them unilaterally (broadcast first).
3. Mock cross-Track downstream dependencies, mark `// TODO` with the real source, and swap in real implementations during Phase 2 integration.

Design intent that won't be obvious from code: **online and offline feature computation must stay identical** (same feature names, same logic). Feature inconsistency / data leakage is the #1 way recommendation quality collapses after deployment. Every layer is designed to degrade gracefully — hot-recall is the always-on fallback, embedding falls back to local BGE/ONNX, and rank falls back to rule scoring if the ONNX model fails to load.
