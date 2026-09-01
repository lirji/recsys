package com.recsys.common.recall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecallContextSequenceTest {
    @Test
    void seedAndChronologicalSequenceHaveIndependentSemantics() {
        RecallContext base = new RecallContext(1, 10, "home", List.of(), Map.of());
        assertNull(base.recentPositiveItems());
        assertNull(base.behaviorSequence());
        RecallContext both = base.withRecentPositiveItems(List.of(9L, 8L))
                .withBehaviorSequence(List.of(1L, 2L, 3L));
        assertEquals(List.of(9L, 8L), both.recentPositiveItems());
        assertEquals(List.of(1L, 2L, 3L), both.behaviorSequence());
    }
}
