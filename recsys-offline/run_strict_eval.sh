#!/usr/bin/env bash
#
# 严格离线评估(strict eval)—— 消除 docs/04 §9 #4 的乐观偏置。
#
# 默认 eval 复用的 i2i/swing/u2u/hot/user-embedding 存储是用**全量**行为(含留出期 ts>splitTs)聚合的,
# 指标偏乐观。本脚本:
#   1) 取与 eval 相同的时间切分点 splitTs;
#   2) 用 --max-ts=splitTs 重建上述 5 个存储为"只用切分点前行为"的无泄漏版本;
#   3) 跑 eval 得严格指标;
#   4) EXIT 时(含中断/出错)自动用全量重建恢复线上存储。
#
# 残余乐观(已知、未严格化,需要时另行处理):
#   - TWO_TOWER:item_tower_embedding 来自全量 Python 训练样本,严格化需按 splitTs 重造样本并重训;
#   - SEMANTIC:其 as-of 伪 query 读用户近期行为标题,未按 splitTs 过滤;
#   - VECTOR 的 item_embedding 是 BGE 内容向量(与行为无关),非泄漏,无需重建;user_embedding 已重建。
#
# 用法:bash recsys-offline/run_strict_eval.sh
set -euo pipefail
cd "$(dirname "$0")/.."   # repo 根目录
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
# 与乐观基线同口径:用本地 BGE 向量化(否则 eval 的 SEMANTIC 路因 Gemini 403 恒空,无法对齐对比)
export EMBEDDING_PROVIDER="${EMBEDDING_PROVIDER:-local}"

run() {  # $1 = --job=... 参数串(整串作为 spring-boot.run.arguments)
    mvn -q -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments="$1"
}

rebuild() {  # $1 = max-ts(空=全量);依次重建 5 个行为派生存储
    local ts="$1" suffix=""
    [ -n "$ts" ] && suffix=" --max-ts=$ts"
    for job in item-cf swing user-cf hot user-embedding; do
        echo "    · $job${suffix:-（全量）}"
        run "--job=$job$suffix" >/dev/null
    done
}

restore() { echo "[restore] 用全量重建恢复线上存储 ..."; rebuild ""; echo "[restore] 完成。"; }

echo "[1/4] 取时间切分点 splitTs（与 eval 同口径,valid-frac=0.2）..."
SPLIT="$(run '--job=eval --print-split' 2>/dev/null | grep -o 'SPLIT_TS=[0-9]*' | cut -d= -f2 || true)"
if [ -z "${SPLIT:-}" ]; then echo "ERROR: 未取到 splitTs（先跑 import-behavior?）"; exit 1; fi
echo "       splitTs=$SPLIT"

# 从此刻起,任何退出都要恢复全量存储
trap restore EXIT

echo "[2/4] 用 ts<=$SPLIT 重建无泄漏存储 ..."
rebuild "$SPLIT"

echo "[3/4] 跑严格 eval（default 排序,与乐观基线同口径）..."
run "--job=eval --k=10,20"

echo "[4/4] 严格指标已写入 recsys-offline/eval/metrics-<最新ts>.csv（pipeline:rank=default 行）。"
echo "       退出时将自动用全量重建恢复存储。对比该行与乐观基线即得"泄漏修正前/后"。"
