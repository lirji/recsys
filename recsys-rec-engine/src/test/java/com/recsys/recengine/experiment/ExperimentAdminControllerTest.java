package com.recsys.recengine.experiment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentAdminControllerTest {

    @Test
    void current_exposesEffectiveEnabledAndMergedWeights() {
        ExperimentProperties props = new ExperimentProperties();
        props.setEnabled(true);
        ExperimentProperties.Variant base = new ExperimentProperties.Variant();
        base.setName("base");
        base.setWeight(50);
        ExperimentProperties.Variant high = new ExperimentProperties.Variant();
        high.setName("high-reserve");
        high.setWeight(50);
        ExperimentProperties.Layer ad = new ExperimentProperties.Layer();
        ad.setSalt("ad-2026a");
        ad.setVariants(List.of(base, high));
        ExperimentProperties.Layer rank = new ExperimentProperties.Layer();
        rank.setSalt("rank-2026a");
        ExperimentProperties.Variant v1 = new ExperimentProperties.Variant();
        v1.setName("v1");
        v1.setWeight(50);
        rank.setVariants(List.of(v1));
        Map<String, ExperimentProperties.Layer> layers = new LinkedHashMap<>();
        layers.put("ad", ad);
        layers.put("rank", rank);
        props.setLayers(layers);

        ExperimentOverrideService ov = mock(ExperimentOverrideService.class);
        when(ov.globalEnabled()).thenReturn(null);
        when(ov.layerEnabled("ad")).thenReturn(false);
        when(ov.layerEnabled("rank")).thenReturn(null);
        when(ov.variantWeight(eq("ad"), eq("base"))).thenReturn(0);
        when(ov.variantWeight(eq("ad"), eq("high-reserve"))).thenReturn(null);
        when(ov.variantWeight(eq("rank"), anyString())).thenReturn(null);
        when(ov.snapshot()).thenReturn(Map.of("ad.enabled", "false", "ad.base.weight", "0"));

        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        ExperimentAdminController ctrl = new ExperimentAdminController(redis, ov, props);

        Map<String, Object> snap = ctrl.current();
        assertEquals(true, snap.get("staticEnabled"));
        assertEquals(true, snap.get("enabled"));

        @SuppressWarnings("unchecked")
        Map<String, Object> staticLayers = (Map<String, Object>) snap.get("staticLayers");
        @SuppressWarnings("unchecked")
        Map<String, Object> adLayer = (Map<String, Object>) staticLayers.get("ad");
        assertEquals(false, adLayer.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> adWeights = (Map<String, Integer>) adLayer.get("variants");
        assertEquals(0, adWeights.get("base"));
        assertEquals(50, adWeights.get("high-reserve"));

        @SuppressWarnings("unchecked")
        Map<String, Object> rankLayer = (Map<String, Object>) staticLayers.get("rank");
        assertEquals(true, rankLayer.get("enabled"));
    }
}
