package com.recsys.recengine.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P3 实验平台化——验证 Redis 动态覆盖真正改变在线分桶(放量/停止/开关不重启)。
 */
class ExperimentOverrideTest {

    private ExperimentProperties props() {
        ExperimentProperties p = new ExperimentProperties();
        p.setEnabled(true);
        ExperimentProperties.Variant a = new ExperimentProperties.Variant();
        a.setName("A");
        a.setWeight(50);
        ExperimentProperties.Variant b = new ExperimentProperties.Variant();
        b.setName("B");
        b.setWeight(50);
        ExperimentProperties.Layer layer = new ExperimentProperties.Layer();
        layer.setSalt("s");
        layer.setVariants(List.of(a, b));
        p.setLayers(new java.util.LinkedHashMap<>(Map.of("rank", layer)));
        return p;
    }

    private long countVariant(ExperimentService svc, String want) {
        long c = 0;
        for (long u = 0; u < 500; u++) {
            if (want.equals(svc.assign(u, "rec").variant("rank"))) {
                c++;
            }
        }
        return c;
    }

    @Test
    void stopVariantB_byWeightZero_allGetA() {
        ExperimentOverrideService ov = mock(ExperimentOverrideService.class);
        when(ov.globalEnabled()).thenReturn(null);
        when(ov.layerEnabled(anyString())).thenReturn(null);
        when(ov.variantWeight("rank", "A")).thenReturn(null);   // A 用静态 50
        when(ov.variantWeight("rank", "B")).thenReturn(0);       // 停 B(放量 A 到 100%)
        ExperimentService svc = new ExperimentService(props(), ov);
        assertEquals(500, countVariant(svc, "A"), "B 权重置 0 → 全部落 A(放量不重启)");
    }

    @Test
    void globalDisable_override_allGetBaseline() {
        ExperimentOverrideService ov = mock(ExperimentOverrideService.class);
        when(ov.globalEnabled()).thenReturn(false);   // 覆盖关闭实验
        when(ov.layerEnabled(anyString())).thenReturn(null);
        when(ov.variantWeight(anyString(), any())).thenReturn(null);
        ExperimentService svc = new ExperimentService(props(), ov);
        assertEquals(500, countVariant(svc, "A"), "全局关 → 全部落基线(首个变体 A)");
    }

    @Test
    void noOverride_splitsAcrossVariants() {
        ExperimentOverrideService ov = mock(ExperimentOverrideService.class);
        when(ov.globalEnabled()).thenReturn(null);
        when(ov.layerEnabled(anyString())).thenReturn(null);
        when(ov.variantWeight(anyString(), any())).thenReturn(null);
        ExperimentService svc = new ExperimentService(props(), ov);
        long a = countVariant(svc, "A");
        // 50/50 静态权重 → 两个变体都拿到相当比例(不全落一边)
        org.junit.jupiter.api.Assertions.assertTrue(a > 100 && a < 400, "50/50 应大致均分,实得 A=" + a);
    }
}
