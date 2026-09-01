package com.recsys.common.recall;

import com.recsys.common.query.TermWeight;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R8-FTS query 词权重在召回上下文及预取副本中的传播契约。 */
class RecallContextTest {

    @Test
    void legacyConstructors_defaultToEmptyQueryTerms() {
        assertTrue(new RecallContext(1, 10, "home").queryTerms().isEmpty());
        assertTrue(new RecallContext(1, 10, "home", List.of(), Map.of()).queryTerms().isEmpty());
    }

    @Test
    void withRecentPositiveItems_preservesQueryTerms() {
        List<TermWeight> terms = List.of(new TermWeight("alien", 2.5));
        RecallContext original = new RecallContext(
                1, 10, "search", List.of(RecallChannel.LEXICAL), Map.of("query", "alien"),
                terms, null, null);

        RecallContext copied = original.withRecentPositiveItems(List.of(42L));

        assertEquals(terms, copied.queryTerms());
        assertEquals(List.of(42L), copied.recentPositiveItems());
    }
}
