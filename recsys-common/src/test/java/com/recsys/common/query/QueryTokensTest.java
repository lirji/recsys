package com.recsys.common.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueryTokens} 契约测试 —— 在线 query 理解与离线 IdfJob 的分词一致性(R8)。
 * IDF 是"离线按语料统计 df、在线按词项查表赋权"的契约,只有两侧分词逐词一致才对得上。
 */
class QueryTokensTest {

    @Test
    void normalize_lowercaseFoldPunctuationCompressSpace() {
        assertEquals("hello world", QueryTokens.normalize("  Hello,  World!  "));
        assertEquals("the dark knight", QueryTokens.normalize("The Dark Knight"));
        assertEquals("", QueryTokens.normalize("   "));
        assertEquals("", QueryTokens.normalize(null));
    }

    @Test
    void tokenize_splitsOnNonWord() {
        assertEquals(List.of("the", "dark", "knight"), QueryTokens.tokenize("The Dark Knight!"));
        // 连字符/多空格/符号都作分隔
        assertEquals(List.of("sci", "fi", "action"), QueryTokens.tokenize("Sci-Fi   action"));
    }

    @Test
    void tokenize_emptyAndNull() {
        assertTrue(QueryTokens.tokenize("").isEmpty());
        assertTrue(QueryTokens.tokenize(null).isEmpty());
        assertTrue(QueryTokens.tokenize("   ,.!  ").isEmpty());
    }

    @Test
    void tokenize_cjkKeptAsLetters() {
        // \p{L} 含中文:CJK 不被当分隔符(英文语料无影响;中文整段成一词,与在线一致即可)
        assertEquals(List.of("科幻", "x"), QueryTokens.tokenize("科幻 X"));
    }

    @Test
    void tokenize_idempotentUnderNormalize() {
        String raw = "  A.I.  Artificial   Intelligence!! ";
        assertEquals(QueryTokens.tokenize(raw), QueryTokens.tokenize(QueryTokens.normalize(raw)),
                "对已归一化串再切词必须与对原串切词一致(在线先 normalize 后 tokenize 的等价保证)");
    }
}
