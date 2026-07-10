#!/usr/bin/env bash
# 多路排序对比:v1规则 / onnx-LightGBM / deepfm / dcn(v2显式交叉) / mmoe多目标(ESMM) / din序列建模,同一 ground truth。
# 前置:mmoe/din 需先 --job=gen-samples-mt 造样本 → train_mmoe.py / train_din.py;dcn 用 samples.csv → train_dcn.py
#       → mvn -pl recsys-rank clean install(把 onnx 打进 jar)。
set -e
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="/Users/liruijun/personal/devUtils/apache-maven-3.9.12/bin:$PATH"
cd /Users/liruijun/personal/LLM/recsys

run() {
  local label="$1"; shift
  echo "######## EVAL: $label ########"
  mvn -q -pl recsys-offline spring-boot:run \
    -Dspring-boot.run.arguments="--job=eval --k=10,20 $*" 2>&1 \
    | grep -E "variant:|^.*EvalJob.*: (K |[0-9]+ |变体|实评用户|metrics 已写入|指标已写入)" || true
}

run "v1-rule"   "--rank-strategy=v1"
run "onnx-lgbm" "--rank-strategy=onnx --recsys.rank.strategy=onnx"
run "deepfm"    "--rank-strategy=deepfm --recsys.rank.strategy=deepfm"
run "dcn"       "--rank-strategy=dcn --recsys.rank.strategy=dcn"
run "mmoe"      "--rank-strategy=mmoe --recsys.rank.strategy=mmoe"
run "din"       "--rank-strategy=din --recsys.rank.strategy=din"

echo "######## 全部完成,汇总 eval/metrics-*.csv ########"
