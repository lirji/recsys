package com.recsys.offline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线作业 DAG 的拓扑规划纯函数(E5)——给定目标作业 + 依赖声明,算出"含全部前置、可安全串行执行"
 * 的拓扑序。无副作用、无 Spring 依赖,便于单测(DagTest);执行/锁/状态记录在 {@link DagJob}。
 */
final class Dag {

    private Dag() {
    }

    /**
     * 规划执行顺序:从 {@code targets} 出发收敛全部传递依赖,再做拓扑排序,使每个作业都排在其依赖之后。
     *
     * @param targets 目标作业名;@param deps 作业 → 其直接依赖列表(缺项视为无依赖)
     * @return 拓扑序作业名列表(含传递依赖)
     * @throws IllegalStateException 依赖成环时抛出(附环上节点)
     */
    static List<String> plan(List<String> targets, Map<String, List<String>> deps) {
        // 1. 从 targets 收敛需要的节点全集(传递闭包)
        Set<String> needed = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(targets);
        while (!stack.isEmpty()) {
            String n = stack.pop();
            if (!needed.add(n)) {
                continue;
            }
            for (String d : deps.getOrDefault(n, List.of())) {
                if (!needed.contains(d)) {
                    stack.push(d);
                }
            }
        }

        // 2. Kahn 拓扑排序(只在 needed 子图内)。入度 = 该节点在 needed 内的依赖数。
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();  // dep → 依赖它的节点
        for (String n : needed) {
            indeg.putIfAbsent(n, 0);
            for (String d : deps.getOrDefault(n, List.of())) {
                if (!needed.contains(d)) {
                    continue;
                }
                indeg.merge(n, 1, Integer::sum);
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(n);
            }
        }
        // 稳定性:按 needed 的插入序取零入度,产出可重现
        Deque<String> ready = new ArrayDeque<>();
        for (String n : needed) {
            if (indeg.get(n) == 0) {
                ready.add(n);
            }
        }
        List<String> order = new ArrayList<>(needed.size());
        while (!ready.isEmpty()) {
            String n = ready.poll();
            order.add(n);
            for (String m : dependents.getOrDefault(n, List.of())) {
                if (indeg.merge(m, -1, Integer::sum) == 0) {
                    ready.add(m);
                }
            }
        }
        if (order.size() != needed.size()) {
            Set<String> cyc = new LinkedHashSet<>(needed);
            order.forEach(cyc::remove);
            throw new IllegalStateException("作业依赖成环,无法拓扑排序;环上节点: " + cyc);
        }
        return order;
    }
}
