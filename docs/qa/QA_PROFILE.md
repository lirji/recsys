# QA Environment Profile

## Scope

- Repository: `recsys`
- Allowed target: localhost only
- Current baseline commit: `6cfa48cd`
- Primary runtime: `scripts/dev-local.sh` + `docker/docker-compose.yml`

## Local endpoints

The active developer overrides are read from the gitignored `scripts/dev-local.env`.

| Component | Expected localhost endpoint |
|---|---|
| Gateway | `http://localhost:9080` |
| PostgreSQL | `localhost:5456` |
| Redis | `localhost:6381` |
| Nacos | `localhost:8858` |
| Console | `http://localhost:9095` |

Container-to-container traffic uses standard internal ports and service DNS names.

## Startup and health checks

```bash
scripts/dev-local.sh infra
scripts/dev-local.sh up
scripts/dev-local.sh status
curl -fsS http://localhost:9080/actuator/health
```

The compose development profile disables edge security by default. If security is enabled locally, use only the configured development login/token flow; do not invent credentials.

## Data and credentials

- PostgreSQL and Redis credentials are supplied through `PG_*` / `REDIS_*` environment variables.
- Do not print or commit credential values from `.env`, `docker/.env`, or `scripts/dev-local.env`.
- Persistent Docker volume `recsys_pgdata` exists, but its current contents must be checked after PostgreSQL starts.
- The offline jobs require PostgreSQL; R7 additionally requires Redis; A6 also requires the sharded `recsys_ds1` database.

## Test artifacts and side effects

- A5 writes `recsys-offline/eval/ad-attribution-<timestamp>.csv`.
- R7 overwrites Redis key `bandit:model` unless its previous value is backed up and restored.
- A6 overwrites `recsys-offline/train/ad_cvr_samples.csv` and may overwrite DFM model/schema/vocabulary assets during training.
- `/api/search-ads` records impressions and may change local budget/pacing counters.
- Back up and restore existing Redis/model/sample artifacts unless the user explicitly asks to keep newly generated outputs.

## Known local caveats

- Docker Desktop is available, but no recsys containers were running during the 2026-09-01 preflight.
- Expected ports 9080/5456/6381 were closed during preflight.
- The required `pgvector/pgvector:pg16` image was not present locally, so starting the declared stack may require a network image pull.
- Testcontainers may fail against Docker Desktop's CLI proxy socket; standard compose/CLI checks should be preferred for this QA run.
