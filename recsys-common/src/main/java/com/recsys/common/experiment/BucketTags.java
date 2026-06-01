package com.recsys.common.experiment;

/**
 * 分桶标签解析:把编排层写入的紧凑 bucketTag(如 {@code recall:plus;rank:onnx;rerank:mmr},
 * 冷启动会带 {@code cold} 前缀)解析成可作为 Micrometer 维度标签的低基数字段。
 *
 * <p>放在 common 是为了让"写曝光"的 rec-engine 与"收点击"的 behavior 用同一套口径解析,
 * 否则两端 tag 不一致,分桶 CTR 就对不上。每层缺省 {@code na},冷启动标 {@code cold=true}。
 *
 * @param recall recall 层 variant 名(或 na)
 * @param rank   rank 层 variant 名(或 na)
 * @param rerank rerank 层 variant 名(或 na)
 * @param cold   是否冷启动桶
 */
public record BucketTags(String recall, String rank, String rerank, boolean cold) {

    private static final String NA = "na";

    /** 解析 bucketTag;null/空 → 全 na。 */
    public static BucketTags parse(String bucketTag) {
        String recall = NA, rank = NA, rerank = NA;
        boolean cold = false;
        if (bucketTag != null && !bucketTag.isBlank()) {
            for (String seg : bucketTag.split(";")) {
                String s = seg.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.equals("cold")) {
                    cold = true;
                } else if (s.startsWith("recall:")) {
                    recall = s.substring("recall:".length());
                } else if (s.startsWith("rank:")) {
                    rank = s.substring("rank:".length());
                } else if (s.startsWith("rerank:")) {
                    rerank = s.substring("rerank:".length());
                }
            }
        }
        return new BucketTags(recall, rank, rerank, cold);
    }
}
