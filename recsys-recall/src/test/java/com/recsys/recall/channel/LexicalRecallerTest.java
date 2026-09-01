package com.recsys.recall.channel;

import com.recsys.common.content.ItemCatalogReader;
import com.recsys.common.query.TermWeight;
import com.recsys.common.recall.RecallChannel;
import com.recsys.common.recall.RecallContext;
import com.recsys.common.recall.RecallItem;
import com.recsys.recall.RecallProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** R8-FTS:Query 理解的 IDF 权重必须原样进入 ItemCatalogReader。 */
class LexicalRecallerTest {

    @Test
    void passesWeightedTermsAndMapsResults() {
        ItemCatalogReader catalog = mock(ItemCatalogReader.class);
        RecallProperties props = new RecallProperties();
        LexicalRecaller recaller = new LexicalRecaller(catalog, props);
        List<TermWeight> terms = List.of(new TermWeight("war", 1.0), new TermWeight("aliens", 3.0));
        RecallContext ctx = new RecallContext(1, 20, "search", List.of(RecallChannel.LEXICAL),
                Map.of("query", "war aliens"), terms, null, null);
        when(catalog.lexicalSearch("war aliens", terms, props.getQuota().getLexical()))
                .thenReturn(List.of(new ItemCatalogReader.ScoredId(42, 0.75)));

        List<RecallItem> out = recaller.recall(ctx);

        assertEquals(1, out.size());
        assertEquals(42, out.get(0).itemId());
        assertEquals(0.75, out.get(0).recallScore(), 1e-12);
        verify(catalog).lexicalSearch("war aliens", terms, props.getQuota().getLexical());
    }

    @Test
    void blankQueryDoesNotHitCatalog() {
        ItemCatalogReader catalog = mock(ItemCatalogReader.class);
        LexicalRecaller recaller = new LexicalRecaller(catalog, new RecallProperties());
        RecallContext ctx = new RecallContext(1, 20, "home", List.of(), Map.of());

        assertTrue(recaller.recall(ctx).isEmpty());
        verifyNoInteractions(catalog);
    }
}
