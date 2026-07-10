package com.recsys.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BgeTokenizer} 契约测试 —— 纯 Java BERT WordPiece 与 HuggingFace {@code BertTokenizer}
 * 的在线/离线一致性(本地向量化的契约,类比 SparseFeatureEncoder/SequenceEncoder)。
 *
 * <p>用一份受控 vocab.txt(行号即 id)锁死算法本身:[CLS]/[SEP] 包裹、贪心最长子词匹配(## 前缀)、
 * OOV→[UNK]、标点独立成词、小写 + 去重音、CJK 单字切分、maxLen 截断。任一步与 HF 定义漂移即变红。
 */
class BgeTokenizerTest {

    // 行号 = token id(0 基):特殊 token 在前,随后是测试用词与子词。
    private static final String VOCAB = String.join("\n",
            "[PAD]",   // 0
            "[UNK]",   // 1
            "[CLS]",   // 2
            "[SEP]",   // 3
            "hello",   // 4
            "world",   // 5
            "play",    // 6
            "##ing",   // 7
            "hi",      // 8
            "!",       // 9
            "foo",     // 10
            "##bar",   // 11
            "中",       // 12
            "文");      // 13

    private Path vocabFile;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        vocabFile = dir.resolve("vocab.txt");
        Files.writeString(vocabFile, VOCAB);
    }

    private BgeTokenizer lower() throws Exception {
        return new BgeTokenizer(vocabFile, true);
    }

    @Test
    void wrapsWithClsSep_maskAndTypes() throws Exception {
        BgeTokenizer.Encoded e = lower().encode("hello world", 32);
        assertArrayEquals(new long[]{2, 4, 5, 3}, e.inputIds(), "[CLS] hello world [SEP]");
        assertArrayEquals(new long[]{1, 1, 1, 1}, e.attentionMask());
        assertArrayEquals(new long[]{0, 0, 0, 0}, e.tokenTypeIds());
        assertEquals(4, e.length());
    }

    @Test
    void wordpiece_greedyLongestMatch_withSubwordPrefix() throws Exception {
        // playing → play + ##ing;foobar → foo + ##bar
        assertArrayEquals(new long[]{2, 6, 7, 3}, lower().encode("playing", 32).inputIds());
        assertArrayEquals(new long[]{2, 10, 11, 3}, lower().encode("foobar", 32).inputIds());
    }

    @Test
    void oov_word_becomesUnk() throws Exception {
        // zzz 无任何子词匹配 → 整词退化为 [UNK]
        assertArrayEquals(new long[]{2, 1, 3}, lower().encode("zzz", 32).inputIds());
    }

    @Test
    void punctuation_splitsIntoOwnToken() throws Exception {
        // "hi!" → hi, !(标点独立成词)
        assertArrayEquals(new long[]{2, 8, 9, 3}, lower().encode("hi!", 32).inputIds());
    }

    @Test
    void lowercaseAndStripAccents() throws Exception {
        // "Héllo" → 小写 + 去重音 → hello
        assertArrayEquals(new long[]{2, 4, 3}, lower().encode("Héllo", 32).inputIds());
    }

    @Test
    void cjk_splitPerCharacter() throws Exception {
        // 中文 → 中 / 文(CJK 单字切分,即使无空格)
        assertArrayEquals(new long[]{2, 12, 13, 3}, lower().encode("中文", 32).inputIds());
    }

    @Test
    void truncation_respectsMaxLen() throws Exception {
        // maxLen=4 → 给 [CLS]/[SEP] 留 2 位,内容只容 2 个 token
        BgeTokenizer.Encoded e = lower().encode("hello world play", 4);
        assertArrayEquals(new long[]{2, 4, 5, 3}, e.inputIds());
        assertEquals(4, e.length());
    }

    @Test
    void missingSpecialToken_throws(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad_vocab.txt");
        Files.writeString(bad, String.join("\n", "[PAD]", "[UNK]", "[SEP]", "hello")); // 缺 [CLS]
        assertThrows(IllegalStateException.class, () -> new BgeTokenizer(bad, true));
    }
}
