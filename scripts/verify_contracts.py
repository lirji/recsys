#!/usr/bin/env python3
"""在线/离线一致性 —— Python 侧契约校验器(E1)。

读取与 Java 侧 RankEncoderContractGoldenTest 相同的 golden 固件, 用 Python 侧
(镜像 train_deepfm/mmoe/din.py 的编码逻辑)计算并断言逐位一致。CI 同时跑
`mvn test`(Java 侧)与本脚本(Python 侧), 两侧共读一份 golden ⇒ 任一侧编码漂移即红。

契约(与 golden 的 _contract 一致):
  - 分桶 = id % buckets(Python 的 % 对正 buckets 天然落非负, 等价 Java Math.floorMod);
  - 类目 OOV/None → 索引 0;
  - 序列定长 seq_len, 右 padding 补 0, 超长只留最近 seq_len 个, 返回有效长度 len。

用法: python scripts/verify_contracts.py   (无三方依赖, 纯标准库)
"""
import json
import os
import sys

GOLDEN = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "recsys-rank", "src", "test", "resources", "contract", "rank_encoders.golden.json",
)


def bucket(idv: int, buckets: int) -> int:
    # 对正 buckets, Python % 已返回 [0, buckets) —— 等价 Java Math.floorMod, 切勿改成会返回负值的实现
    return idv % buckets


def encode_sparse(schema, vocab, case):
    return [
        bucket(case["user_id"], schema["user_buckets"]),
        bucket(case["item_id"], schema["item_buckets"]),
        vocab.get(case["category"], 0) if case["category"] is not None else 0,
    ]


def encode_sequence(schema, case):
    seq_len = schema["seq_len"]
    buckets = schema["item_buckets"]
    items = case["items_oldest_first"][-seq_len:]  # 只留最近 seq_len 个(尾部)
    seq = [bucket(x, buckets) for x in items]
    length = len(seq)
    seq = seq + [0] * (seq_len - length)  # 右 padding
    return seq, length


def main() -> int:
    with open(GOLDEN, encoding="utf-8") as f:
        golden = json.load(f)

    failures = []

    sp = golden["sparse"]
    for c in sp["cases"]:
        got = encode_sparse(sp["schema"], sp["category_vocab"], c)
        if got != c["expected"]:
            failures.append(f"[sparse] user={c['user_id']} item={c['item_id']} cat={c['category']}: "
                            f"expected {c['expected']} got {got}")

    sq = golden["sequence"]
    for c in sq["cases"]:
        seq, length = encode_sequence(sq["schema"], c)
        if seq != c["expected_seq"] or length != c["expected_len"]:
            failures.append(f"[sequence] items={c['items_oldest_first']}: "
                            f"expected {c['expected_seq']}/{c['expected_len']} got {seq}/{length}")

    total = len(sp["cases"]) + len(sq["cases"])
    if failures:
        print(f"CONTRACT MISMATCH ({len(failures)}/{total} cases): 在线(Java)/离线(Python) 编码已漂移!")
        for msg in failures:
            print("  - " + msg)
        return 1

    print(f"OK: {total} 个 golden 用例 Python 侧编码与固件逐位一致(与 Java 侧共读同一 golden)。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
