#!/usr/bin/env bash
# ONNX 单文件自包含校验(E3)。
#
# 背景(CLAUDE.md):导出器被强制为单个自包含 .onnx —— Java 从 classpath 以字节数组加载模型,
# 若 PyTorch 导出出外部权重(*.onnx.data / *_data / 同目录游离 .bin/.data),在线加载即缺权重而崩。
# 本脚本只查"会被打进 jar 的模型资源"(src/main/resources),排除 venv/target。CI 失败即拦截。
set -euo pipefail

cd "$(dirname "$0")/.."

onnx_files=$(find . -path '*/src/main/resources/*' -name '*.onnx' \
    -not -path '*/target/*' -not -path '*/.venv/*' | sort)

if [ -z "$onnx_files" ]; then
    echo "未发现打包模型资源(*.onnx),跳过。"
    exit 0
fi

fail=0
count=0
while IFS= read -r f; do
    [ -z "$f" ] && continue
    count=$((count + 1))
    dir=$(dirname "$f")

    # 1) 模型非空
    if [ ! -s "$f" ]; then
        echo "✗ 空模型文件: $f"
        fail=1
    fi

    # 2) 无同名外部权重(external data)伴随文件
    for ext in "${f}.data" "${f%.onnx}.data" "${f%.onnx}_data" "${f%.onnx}.onnx_data"; do
        if [ -e "$ext" ]; then
            echo "✗ 外部权重存在,破坏 classpath 单文件加载: $ext (属于 $f)"
            fail=1
        fi
    done

    # 3) 同目录不应有游离的 .data/.bin/.pb 权重(external data 的常见落盘形态)
    stray=$(find "$dir" -maxdepth 1 \( -name '*.data' -o -name '*.bin' -o -name '*.pb' \) 2>/dev/null || true)
    if [ -n "$stray" ]; then
        echo "✗ 模型目录存在游离权重文件(疑似 external data): $stray"
        fail=1
    fi
done <<< "$onnx_files"

if [ "$fail" -ne 0 ]; then
    echo "ONNX 自包含校验失败:重新导出时确保 dynamo=False / 单文件(见 CLAUDE.md 排序模型段)。"
    exit 1
fi

echo "✓ ONNX 自包含校验通过:$count 个打包模型均为单文件、无外部权重。"
