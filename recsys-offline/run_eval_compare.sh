#!/usr/bin/env bash
# 三路排序对比:v1 规则 / onnx LightGBM / deepfm 深度模型,同一 ground truth。
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

run "v1-rule"  "--rank-strategy=v1"
run "onnx-lgbm" "--rank-strategy=onnx --recsys.rank.strategy=onnx"
run "deepfm"   "--rank-strategy=deepfm --recsys.rank.strategy=deepfm"

echo "######## 全部完成,汇总 eval/metrics-*.csv ########"
