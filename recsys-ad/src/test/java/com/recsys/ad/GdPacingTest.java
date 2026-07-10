package com.recsys.ad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GdPacing} 单测(A4)——GD 保量的投放进度分配:落后于时间进度才优先出、否则让位竞价。
 */
class GdPacingTest {

    private static final long START = 0;
    private static final long END = 1000;   // 窗口 [0,1000]

    @Test
    void expectedDelivered_linearInTime() {
        assertEquals(0.0, GdPacing.expectedDelivered(1000, START, END, 0), 1e-9);
        assertEquals(500.0, GdPacing.expectedDelivered(1000, START, END, 500), 1e-9); // 半程 → 一半
        assertEquals(1000.0, GdPacing.expectedDelivered(1000, START, END, END), 1e-9);
        assertEquals(1000.0, GdPacing.expectedDelivered(1000, START, END, 9999), 1e-9); // 超窗口封顶
    }

    @Test
    void behind_whenDeliveredBelowPace() {
        // 半程期望 500,实际只交付 200 → 落后 → 保量
        assertTrue(GdPacing.behind(200, 1000, START, END, 500, 0.02));
        double urg = GdPacing.urgency(200, 1000, START, END, 500);
        assertEquals((500.0 - 200) / 500, urg, 1e-9);
    }

    @Test
    void notBehind_whenOnOrAheadOfPace_yieldsToAuction() {
        // 半程期望 500,已交付 600(超前)→ 不保量
        assertFalse(GdPacing.behind(600, 1000, START, END, 500, 0.02));
        assertTrue(GdPacing.urgency(600, 1000, START, END, 500) <= 0);
    }

    @Test
    void finishedContract_notServed() {
        // 已交付满 → 紧迫度 <0,不再出
        assertTrue(GdPacing.urgency(1000, 1000, START, END, 500) < 0);
        assertFalse(GdPacing.behind(1000, 1000, START, END, 500, 0.02));
    }
}
