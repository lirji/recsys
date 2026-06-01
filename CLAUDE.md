# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A production-oriented recommendation-system scaffold: Java + Spring Boot 3.2, Maven multi-module single repo. It implements the classic funnel **recall → rank → rerank**, with an offline/near-line path (embeddings, collaborative filtering, model training) feeding online stores. Built to run fully locally via `docker compose`, but structured to split into microservices later.

**Current state: Phase 0 (scaffold).** Only `recsys-common` (the shared contracts) and empty `@SpringBootApplication` stubs exist. The feature modules are awaiting implementation per the Tracks in `PLAN.md`. When implementing, follow the design docs in `docs/` — they are the source of truth.

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

DB schema lives in `recsys-offline/sql/` and is auto-executed by the postgres container on first boot (mounted to `docker-entrypoint-initdb.d`). To re-run it, drop the `pgdata` volume.

## Architecture

**Module layout** — `[app]` = runnable service (port), `[lib]` = computation/domain library (no executable jar):

- `recsys-common` — shared contracts: interfaces, DTOs (records), constants, Redis keys. **The foundation of parallel development; depended on by everything. Changing it ripples everywhere — broadcast before editing.**
- `recsys-rec-engine` [app :8081] — orchestration, the main external entry. `GET /api/recommend`.
- `recsys-gateway` [app :8080] — Spring Cloud Gateway routing.
- `recsys-behavior` [app :8082] — behavior ingestion. `POST /api/behavior` → Kafka or DB.
- `recsys-web` [app :8090] — demo frontend.
- `recsys-offline` [app] — offline jobs: data import, CF batch, embedding backfill, sample generation. `train/` holds Python LightGBM→ONNX scripts.
- `recsys-recall` / `recsys-rank` / `recsys-feature` / `recsys-embedding` / `recsys-content` / `recsys-user` [lib] — the recall, ranking, feature, embedding, content, and user-profile libraries.

**Monolith-first, microservices-ready.** `RecEngineApplication` uses `@SpringBootApplication(scanBasePackages = "com.recsys")` so the `[lib]` modules' `@Component`/`@RestController` beans are all wired into one process. Module boundaries are already drawn, so splitting into independent services later is cheap. Keep cross-module coupling to the `recsys-common` interfaces.

**Request flow** (`docs/02-架构设计.md` §6): rec-engine checks `cache:rec:{userId}` → 4-way recall (vector / i2i / hot / tag) merged & deduped → rank (assemble features + ONNX or rule scoring) → rerank (category diversity + business rules) → truncate to size, build reasons, cache, return.

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
