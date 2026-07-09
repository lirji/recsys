package com.recsys.rank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SparseFeatureEncoder} 契约测试 —— 深度排序稀疏编码的在线/离线一致性(必须与 train_*.py 逐位一致)。
 * 覆盖:floorMod 分桶、类目 OOV=0、向后兼容(无 schema 标志 → width=3/denseOrder=5)、
 * 语义 ID 追加(width=6、缺失补 0)、dense_order 扩展。
 */
class SparseFeatureEncoderTest {

    private static final String VOCAB = "{\"Action\":1,\"Drama\":2}";

    private Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void baseEncode_bucketsAndCatIndex(@TempDir Path dir) throws Exception {
        Path schema = write(dir, "s.json", "{\"user_buckets\":100,\"item_buckets\":1000}");
        Path vocab = write(dir, "v.json", VOCAB);
        SparseFeatureEncoder enc = SparseFeatureEncoder.load(schema.toString(), vocab.toString());

        // floorMod 分桶 + 类目索引;OOV 类目 → 0
        assertArrayEquals(new long[]{123 % 100, 4567 % 1000, 1}, enc.encode(123, 4567, "Action"));
        assertArrayEquals(new long[]{5, 5, 0}, enc.encode(5, 5, "Unknown"), "未登录类目 → 0");
        assertArrayEquals(new long[]{5, 5, 0}, enc.encode(5, 5, null), "null 类目 → 0");
        assertEquals(3, enc.width());
    }

    @Test
    void backwardCompat_denseOrderDefaultsToFeatureOrder(@TempDir Path dir) throws Exception {
        Path schema = write(dir, "s.json", "{\"user_buckets\":100,\"item_buckets\":1000}");
        Path vocab = write(dir, "v.json", VOCAB);
        SparseFeatureEncoder enc = SparseFeatureEncoder.load(schema.toString(), vocab.toString());
        // 无 dense_order 声明 → 回退基础 5 维(旧模型零影响)
        assertEquals(FeatureAssembler.FEATURE_ORDER, enc.denseOrder());
    }

    @Test
    void semanticId_appendsCodewords(@TempDir Path dir) throws Exception {
        Path map = write(dir, "sid.json", "{\"4567\":[7,8,9]}");
        Path schema = write(dir, "s.json",
                "{\"user_buckets\":100,\"item_buckets\":1000,\"semantic_id\":true,\"semantic_id_map\":\""
                        + map.toString().replace("\\", "\\\\") + "\"}");
        Path vocab = write(dir, "v.json", VOCAB);
        SparseFeatureEncoder enc = SparseFeatureEncoder.load(schema.toString(), vocab.toString());

        assertEquals(6, enc.width());
        assertArrayEquals(new long[]{67, 567, 1, 7, 8, 9}, enc.encode(123 + 4444, 4567, "Action"));
        // 缺失 item → 补 0,0,0(保持定宽)
        assertArrayEquals(new long[]{1, 1, 2, 0, 0, 0}, enc.encode(1, 1, "Drama"));
    }

    @Test
    void extendedDenseOrder_parsedFromSchema(@TempDir Path dir) throws Exception {
        Path schema = write(dir, "s.json",
                "{\"user_buckets\":100,\"item_buckets\":1000,\"dense_order\":"
                        + "[\"item_pop_norm\",\"item_avg_rating\",\"user_act_norm\",\"user_avg_rating\","
                        + "\"user_cat_affinity\",\"user_cat_cnt_norm\",\"user_cat_ratio\",\"item_rating_std\"]}");
        Path vocab = write(dir, "v.json", VOCAB);
        SparseFeatureEncoder enc = SparseFeatureEncoder.load(schema.toString(), vocab.toString());
        assertEquals(8, enc.denseOrder().size());
        assertEquals("item_rating_std", enc.denseOrder().get(7));
    }
}
