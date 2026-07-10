package com.recsys.rank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SequenceEncoder} 契约测试 —— DIN 行为序列编码的在线/离线一致性
 * (必须与 train_din.py 的 {@code encode_seq} 逐位一致)。
 *
 * <p>锁死四条契约:定长右 padding、floorMod 分桶(负 id 也落非负桶)、
 * 超长只留最近 seqLen、空序列 len=0(模型据此把池化置 0,冷用户不崩)。
 */
class SequenceEncoderTest {

    private SequenceEncoder load(Path dir, int seqLen, int itemBuckets) throws Exception {
        Path schema = dir.resolve("din_schema.json");
        Files.writeString(schema, "{\"seq_len\":" + seqLen + ",\"item_buckets\":" + itemBuckets + "}");
        return SequenceEncoder.load(schema.toString());
    }

    @Test
    void rightPad_oldestFirst_lenTracked(@TempDir Path dir) throws Exception {
        SequenceEncoder enc = load(dir, 5, 1000);
        SequenceEncoder.Encoded out = enc.encode(List.of(10L, 20L, 30L));
        // 有效项在前(oldest→newest),右补 0;len=有效数
        assertArrayEquals(new long[]{10, 20, 30, 0, 0}, out.seq());
        assertEquals(3, out.len());
        assertEquals(5, enc.seqLen());
    }

    @Test
    void floorMod_bucketing_notNativeMod(@TempDir Path dir) throws Exception {
        SequenceEncoder enc = load(dir, 3, 1000);
        // floorMod(-1,1000)=999(而非 Java 原生 % 的 -1),这是与 Python 一致的关键
        SequenceEncoder.Encoded out = enc.encode(List.of(-1L, 1030L));
        assertArrayEquals(new long[]{999, 30, 0}, out.seq());
        assertEquals(2, out.len());
    }

    @Test
    void overLength_keepsMostRecent(@TempDir Path dir) throws Exception {
        SequenceEncoder enc = load(dir, 3, 1000);
        // 7 个,seqLen=3 → 只留最近 3 个(尾部),仍 oldest→newest
        SequenceEncoder.Encoded out = enc.encode(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertArrayEquals(new long[]{5, 6, 7}, out.seq());
        assertEquals(3, out.len());
    }

    @Test
    void empty_and_null_giveZeroLen(@TempDir Path dir) throws Exception {
        SequenceEncoder enc = load(dir, 4, 1000);
        assertArrayEquals(new long[]{0, 0, 0, 0}, enc.encode(List.of()).seq());
        assertEquals(0, enc.encode(List.of()).len());
        assertArrayEquals(new long[]{0, 0, 0, 0}, enc.encode(null).seq());
        assertEquals(0, enc.encode(null).len());
    }

    @Test
    void missingSchemaField_throws(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("bad.json");
        Files.writeString(schema, "{\"seq_len\":5}"); // 缺 item_buckets
        assertThrows(IllegalStateException.class, () -> SequenceEncoder.load(schema.toString()));
    }
}
