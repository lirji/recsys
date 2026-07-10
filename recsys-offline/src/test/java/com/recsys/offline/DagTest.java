package com.recsys.offline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Dag} 拓扑规划单测(E5)——保证依赖先于作业执行、目标自动补齐传递依赖、成环可检出。
 */
class DagTest {

    private static final Map<String, List<String>> DEPS = Map.of(
            "b", List.of("a"),
            "c", List.of("a"),
            "d", List.of("b", "c"));

    @Test
    void includesTransitiveDeps_andOrdersDepsFirst() {
        List<String> order = Dag.plan(List.of("d"), DEPS);
        assertEquals(4, order.size(), "d 的传递依赖 a/b/c 应全部补齐");
        // 每个依赖必须排在其依赖者之前
        assertBefore(order, "a", "b");
        assertBefore(order, "a", "c");
        assertBefore(order, "b", "d");
        assertBefore(order, "c", "d");
    }

    @Test
    void partialTarget_onlyNeededSubgraph() {
        List<String> order = Dag.plan(List.of("b"), DEPS);
        assertEquals(List.of("a", "b"), order, "只需 b 时不应拉入 c/d");
    }

    @Test
    void noDeps_returnsTargetsThemselves() {
        List<String> order = Dag.plan(List.of("x", "y"), Map.of());
        assertTrue(order.containsAll(List.of("x", "y")) && order.size() == 2);
    }

    @Test
    void cycle_throws() {
        Map<String, List<String>> cyclic = Map.of("p", List.of("q"), "q", List.of("p"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Dag.plan(List.of("p"), cyclic));
        assertTrue(ex.getMessage().contains("环"), "应报环上节点");
    }

    private static void assertBefore(List<String> order, String earlier, String later) {
        assertTrue(order.indexOf(earlier) < order.indexOf(later),
                earlier + " 应排在 " + later + " 之前,实得 " + order);
    }
}
