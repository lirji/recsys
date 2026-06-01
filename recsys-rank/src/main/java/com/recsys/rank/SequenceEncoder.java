package com.recsys.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DIN 行为序列编码器:把用户历史 item 序列编码成定长 item 桶向量 + 有效长度。
 *
 * <p><b>在线/离线一致性契约</b>(必须与 train_din.py 的 {@code encode_seq} 逐位一致):
 * <ul>
 *   <li>定长 {@code seqLen}(= din_schema.json 的 {@code seq_len},训练侧 SEQ_LEN);</li>
 *   <li><b>右 padding</b>:有效项在前(oldest→newest),不足补 0 在后;</li>
 *   <li>item 桶 = floorMod(itemId, itemBuckets),桶大小取自 din_schema.json;</li>
 *   <li>同时返回有效长度 {@code len},模型用它做注意力掩码(空序列 → 池化置 0)。</li>
 * </ul>
 * 入参 itemIds 顺序须为 oldest→newest(与训练侧 seq_items 一致);超过 seqLen 只保留最近的。
 */
public final class SequenceEncoder {

    private final int seqLen;
    private final int itemBuckets;

    private SequenceEncoder(int seqLen, int itemBuckets) {
        this.seqLen = seqLen;
        this.itemBuckets = itemBuckets;
    }

    public static SequenceEncoder load(String schemaPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schema = mapper.readTree(readBytes(schemaPath));
        int seqLen = required(schema, "seq_len").asInt();
        int itemBuckets = required(schema, "item_buckets").asInt();
        return new SequenceEncoder(seqLen, itemBuckets);
    }

    public int seqLen() {
        return seqLen;
    }

    /**
     * 编码用户历史序列(oldest→newest)→ [桶序列(定长 seqLen,右 pad 0), 有效长度]。
     *
     * @return {@code Encoded(long[] seq, int len)}
     */
    public Encoded encode(List<Long> itemIdsOldestFirst) {
        long[] seq = new long[seqLen];
        if (itemIdsOldestFirst == null || itemIdsOldestFirst.isEmpty()) {
            return new Encoded(seq, 0);
        }
        // 只保留最近 seqLen 个(列表尾部 = 最近);保持 oldest→newest 顺序右 pad
        int total = itemIdsOldestFirst.size();
        int from = Math.max(0, total - seqLen);
        int len = total - from;
        for (int j = 0; j < len; j++) {
            seq[j] = Math.floorMod(itemIdsOldestFirst.get(from + j), itemBuckets);
        }
        return new Encoded(seq, len);
    }

    /** 编码结果:定长桶序列 + 有效长度。 */
    public record Encoded(long[] seq, int len) {
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            throw new IllegalStateException("din_schema.json 缺字段: " + field);
        }
        return v;
    }

    private static byte[] readBytes(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String cp = path.substring("classpath:".length());
            try (InputStream in = SequenceEncoder.class.getClassLoader().getResourceAsStream(cp)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 未找到: " + cp);
                }
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(Path.of(path));
    }
}
