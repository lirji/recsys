package com.recsys.rank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 跨语言 golden 契约测试(E1)——锁死排序稀疏/序列编码的在线(Java)/离线(Python)一致性。
 *
 * <p>本测试与 {@code scripts/verify_contracts.py} 读取<b>同一份</b> golden 固件
 * ({@code /contract/rank_encoders.golden.json}),各自用本侧真实编码器计算并断言逐位一致。
 * 只要 Java 的 {@link SparseFeatureEncoder}/{@link SequenceEncoder} 与 Python 的
 * {@code train_deepfm/mmoe/din.py} 任一侧编码逻辑漂移,两侧至少一处变红——这正是
 * "在线/离线特征不一致导致上线崩" 的第一道自动化防线(CLAUDE.md 核心设计意图)。
 */
class RankEncoderContractGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode loadGolden() throws Exception {
        try (var in = getClass().getResourceAsStream("/contract/rank_encoders.golden.json")) {
            if (in == null) {
                throw new IllegalStateException("golden 固件缺失: /contract/rank_encoders.golden.json");
            }
            return MAPPER.readTree(in.readAllBytes());
        }
    }

    @Test
    void sparseEncoder_matchesGolden(@TempDir Path dir) throws Exception {
        JsonNode g = loadGolden().get("sparse");
        Path schema = write(dir, "schema.json", MAPPER.writeValueAsString(g.get("schema")));
        Path vocab = write(dir, "vocab.json", MAPPER.writeValueAsString(g.get("category_vocab")));
        SparseFeatureEncoder enc = SparseFeatureEncoder.load(schema.toString(), vocab.toString());

        for (JsonNode c : g.get("cases")) {
            long userId = c.get("user_id").asLong();
            long itemId = c.get("item_id").asLong();
            String cat = c.get("category").isNull() ? null : c.get("category").asText();
            long[] actual = enc.encode(userId, itemId, cat);
            assertArrayEquals(toLongArray(c.get("expected")), actual,
                    "sparse 编码漂移: user=" + userId + " item=" + itemId + " cat=" + cat);
        }
    }

    @Test
    void sequenceEncoder_matchesGolden(@TempDir Path dir) throws Exception {
        JsonNode g = loadGolden().get("sequence");
        Path schema = write(dir, "din_schema.json", MAPPER.writeValueAsString(g.get("schema")));
        SequenceEncoder enc = SequenceEncoder.load(schema.toString());

        for (JsonNode c : g.get("cases")) {
            List<Long> items = new ArrayList<>();
            for (JsonNode it : c.get("items_oldest_first")) {
                items.add(it.asLong());
            }
            SequenceEncoder.Encoded out = enc.encode(items);
            assertArrayEquals(toLongArray(c.get("expected_seq")), out.seq(),
                    "sequence 编码漂移: items=" + items);
            assertEquals(c.get("expected_len").asInt(), out.len(),
                    "sequence 有效长度漂移: items=" + items);
        }
    }

    private static long[] toLongArray(JsonNode arr) {
        long[] out = new long[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).asLong();
        }
        return out;
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }
}
