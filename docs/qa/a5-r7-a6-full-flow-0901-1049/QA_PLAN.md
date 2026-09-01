# A5 / R7 / A6 Real Full-Flow QA Plan

> **执行状态：已执行（2026-09-01）**。最终证据与结论见同目录 `QA_REPORT.md`：A5、R7 通过；A6 功能/合成/组件契约完成，但真实数据在线闭环未通过。本文保留执行前基线和验收门槛，不再作为当前状态页。

## Objective

Verify the three items against the persistent local PostgreSQL/Redis data rather than synthetic fixtures:

- A5 multi-touch attribution: real `ad_event` → attribution job → auditable conserved-credit report.
- R7 contextual bandit: real recommendation impressions/feedback → `bandit-stats` → Redis model → online scoring and fallback.
- A6 delayed-feedback DFM: real ad clicks/conversions + sharded ad mapping → samples → training/ONNX → online search-ad pCVR path.

## Current preflight result

- Docker Desktop responds, but no recsys containers are running.
- PostgreSQL `:5456`, Redis `:6381`, Gateway `:9080`, and Console `:9095` are not listening.
- Persistent volume `recsys_pgdata` exists; its data has not yet been mounted and queried.
- No A5 `ad-attribution-*.csv` artifact exists.
- R7 has no filesystem model artifact; the authoritative `bandit:model` key cannot be inspected while Redis is down.
- A6 has a 20,000-row sample with 4,720 conversions (23.6%) and a valid self-contained IR9 ONNX model. This matches the documented synthetic validation scale, not the documented real database scale, so it is not accepted as real full-flow evidence.

## Approval boundary and safety

Execution starts only after user approval because it will:

- start localhost containers and may pull `pgvector/pgvector:pg16` from the network;
- write a new A5 CSV;
- temporarily write/replace Redis `bandit:model`;
- generate A6 samples/models and call an endpoint that logs local ad impressions.

Before mutation, snapshot the existing Redis key and A6 artifacts to a temporary directory. Restore them after validation unless the user requests otherwise. Do not clear tables, truncate data, run seed jobs, or delete Docker volumes.

## Test cases

| ID | Priority | Test | Expected result |
|---|---|---|---|
| PRE-01 | P0 | Start declared local infrastructure and probe PostgreSQL/Redis | Services are reachable using configured local ports; no authentication error |
| PRE-02 | P0 | Read-only inventory of `ad_event`, `user_behavior`, `item`, `feat:*`, sharded `ad` | Required tables/keys exist; counts are sufficient for each job |
| A5-01 | P0 | Run `ad-attribution --days=0 --model=position` on existing events | Job completes and writes a non-empty timestamped CSV |
| A5-02 | P0 | Reconcile report totals with source conversions | `sum(mta_credit)` approximately equals eligible conversions; per row `cta_credit + vta_credit ≈ mta_credit` |
| A5-03 | P1 | Inspect path depth and `credit_delta` distribution | At least one multi-touch path and one non-zero credit delta exist; otherwise report is valid but non-demonstrative |
| R7-01 | P0 | Snapshot `bandit:model`, then run `bandit-stats --days=0` | Redis receives parseable model JSON with feature order length 5 and `n` equal to processed impressions |
| R7-02 | P0 | Validate model matrices and online reader | Model is finite/invertible and `BanditScorer` reads it without fallback |
| R7-03 | P0 | Compare one deterministic recommendation with bandit disabled/enabled | Enabled request has a non-zero bandit contribution for at least one item while response remains valid |
| R7-04 | P1 | Remove/disable model temporarily and request again | Online path falls back to zero bandit contribution without 5xx |
| A6-01 | P0 | Generate samples from existing real ad events and sharded ad mapping | Non-empty CSV; every row maps to item; converted delays and censored rows are structurally valid |
| A6-02 | P0 | Train DFM and validate exported ONNX | Training finishes; ONNX is IR9, self-contained, finite, and conforms to dense `[N,5]` + sparse `[N,3]` → pCVR `[N,1]` |
| A6-03 | P0 | Start online path with `AD_CVR_DFM_ENABLED=true` | Service log confirms DFM model ready; search-ad request succeeds and pCVR values remain in `[0,1]` |
| A6-04 | P1 | Compare DFM enabled vs disabled on the same request | Enabled path demonstrably uses DFM output; disabled path uses the documented fallback and remains successful |
| CLEAN-01 | P0 | Restore Redis key and backed-up sample/model assets | Pre-test local state is restored; generated QA report/evidence is retained |

## Pass criteria

- A5 passes only with a generated real-data report and conservation checks, not merely unit tests.
- R7 passes only if both offline materialization and online consumption are observed.
- A6 passes only if samples originate from the mounted real database and the enabled online request consumes the newly trained model.
- Environment/data insufficiency is reported as blocked, not passed and not silently replaced with synthetic data.
