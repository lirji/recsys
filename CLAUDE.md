# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A production-oriented recommendation-system scaffold: Java + Spring Boot 3.2, Maven multi-module single repo. It implements the classic funnel **recall → rank → rerank**, with an offline/near-line path (embeddings, collaborative filtering, model training) feeding online stores. Built to run fully locally via `docker compose`, but structured to split into microservices later.

**Current state: core funnel implemented & verified locally (M1–M3).** Recall (8 channels: vector/i2i/hot/tag + u2u/swing/semantic/cold), rank (rule + LightGBM→ONNX), pluggable rerank (diversity/mmr/none), layered A/B experiments (recall×rank×rerank, deterministic bucketing, exposure logged to `user_behavior.bucket`), cold-start (detector + interest onboarding) are all in place. Offline jobs feed the online stores. The design docs in `docs/` are the source of truth — keep them in sync when changing behavior.

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

Job names (run roughly in this order to bootstrap the stores): `import-items`, `import-behavior`, `backfill-embedding`, `user-embedding`, `item-cf`, `user-cf`, `swing`, `hot`, `build-features`, `gen-samples`. Running with no `--job` logs the available set and exits. **Evaluation jobs** (run after the stores are built): `eval` (offline recommendation quality — time-split held-out positives → reuses the online recall→rank pipeline → Precision/Recall/NDCG/MAP/MRR/HitRate/Coverage/Diversity/Novelty @K; supports `--k=10,20,50`, `--recall-only` for per-channel recall, `--rank-strategy=v1|onnx|deepfm`, `--max-users`; writes `eval/metrics-<ts>.csv`) and `ab-report` (online per-bucket CTR from the `IMPRESSION`/click exposure logs; writes `eval/ab-report-<ts>.csv`).

**Ranking models (`recsys-offline/train/`)** — two trainers feed the online ONNX path, selected by `recsys.rank.strategy` (`RANK_STRATEGY` env), both falling back to rule scoring if the model is absent/fails:
- `train_lgbm.py` → `model.onnx` (LightGBM, `strategy=onnx`): single dense input `[N,5]` (the 5 `FeatureAssembler` features).
- `train_deepfm.py` → `model_deepfm.onnx` + `rank_schema.json` + `category_vocab.json` (PyTorch DeepFM, `strategy=deepfm`): **dual input** `dense[N,5]` float + `sparse[N,3]` int64 (userId/itemId/category embeddings). Online `DeepFmRankService` encodes the sparse ids via `SparseFeatureEncoder` whose bucketing (`floorMod`) + category vocab must match the trainer exactly — that's the online/offline contract for the embeddings, analogous to `FeatureAssembler` for dense. **Both trainers share one `samples.csv`** (gen-samples emits dense + raw `user_id,item_id,category`); `train_lgbm` ignores the raw id columns. DeepFM uses a **random** train/valid split (id-embedding models need every id seen in train), so its AUC is not comparable to LightGBM's time-split AUC — use the `eval` job for an apples-to-apples ranking comparison. DeepFM needs `pip install -r requirements-deepfm.txt` (torch + onnxscript); the exporter is forced to a single self-contained `.onnx` (no external `.data`) because Java loads it from classpath as a byte array. After retraining, **`mvn -pl recsys-rank clean install`** to repack the model into the jar (plain `install` leaves stale resources in `target/classes`).

DB schema lives in `recsys-offline/sql/01_schema.sql` and is auto-executed by the postgres container on first boot (mounted to `docker-entrypoint-initdb.d`). To re-run it, drop the `pgdata` volume.

> Note: there are currently no automated tests in the repo; the `mvn ... test` lines above are the conventions to use when adding them.

## Architecture

**Module layout** — `[app]` = runnable service (port), `[lib]` = computation/domain library (no executable jar):

- `recsys-common` — shared contracts: interfaces, DTOs (records), constants, Redis keys. **The foundation of parallel development; depended on by everything. Changing it ripples everywhere — broadcast before editing.**
- `recsys-rec-engine` [app :8081] — orchestration, the main external entry. `GET /api/recommend`; cold-start interest onboarding via `GET/POST /api/user/{id}/interests`.
- `recsys-gateway` [app :8080] — Spring Cloud Gateway routing.
- `recsys-behavior` [app :8082] — behavior ingestion. `POST /api/behavior` → Kafka or DB.
- `recsys-web` [app :8090] — demo frontend.
- `recsys-offline` [app] — offline jobs: data import, CF batch, embedding backfill, sample generation. `train/` holds Python LightGBM→ONNX scripts.
- `recsys-recall` / `recsys-rank` / `recsys-feature` / `recsys-embedding` / `recsys-content` / `recsys-user` [lib] — the recall, ranking, feature, embedding, content, and user-profile libraries.

**Monolith-first, microservices-ready.** `RecEngineApplication` uses `@SpringBootApplication(scanBasePackages = "com.recsys")` so the `[lib]` modules' `@Component`/`@RestController` beans are all wired into one process. Module boundaries are already drawn, so splitting into independent services later is cheap. Keep cross-module coupling to the `recsys-common` interfaces.

**Request flow** (`docs/02-架构设计.md` §6): rec-engine checks `cache:rec:{userId}` → cold-start detection + layered A/B assignment → multi-channel recall (channels gated by the experiment's recall variant / cold-start override) merged & deduped (primary source chosen by priority) → rank (assemble features + ONNX or rule scoring, strategy per rank variant) → fuse recall+rank scores → rerank (strategy per rerank variant; cold-start forces strong diversity) → truncate, build reasons, log exposure with bucket tag, cache, return.

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
