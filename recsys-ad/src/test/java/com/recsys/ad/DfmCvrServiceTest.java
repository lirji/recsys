package com.recsys.ad;

import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DfmCvrService} 在线打分路单测(A6)——加载打包进 classpath 的 DFM ad-CVR ONNX
 * (由 train_dfm.py 导出),mock 特征服务,验证:模型就绪、pCVR∈[0,1]、缺模型零降级。
 * 覆盖 Java 侧 onnxruntime 加载 + dense/sparse 编码 + 单输出提取(train_dfm.py 的 IR9 导出的在线契约)。
 */
class DfmCvrServiceTest {

    private DfmCvrService service(AdProperties props) {
        FeatureService fs = mock(FeatureService.class);
        when(fs.userFeatures(anyLong())).thenReturn(Map.of());
        when(fs.itemFeatures(anyCollection())).thenReturn(Map.of());
        ContentService cs = mock(ContentService.class);
        when(cs.findByIds(anyList())).thenReturn(Map.of());
        DfmCvrService svc = new DfmCvrService(fs, cs, props);
        svc.load();   // 包内可直接触发 @PostConstruct 逻辑(单测无 Spring 容器)
        return svc;
    }

    @Test
    void readyAndScoresInRange() {
        // 默认 Cvr 路径指向 classpath:model/model_dfm_cvr.onnx(打包资源)。
        // 该 .onnx 走 gitignore(由 train_dfm.py 产出),纯净检出/CI 无此文件 → 跳过(不判失败)。
        DfmCvrService svc = service(new AdProperties());
        org.junit.jupiter.api.Assumptions.assumeTrue(svc.isReady(),
                "跳过:需先跑 train_dfm.py 产出 model_dfm_cvr.onnx(gitignore,CI/纯净检出无此模型)");
        Map<Long, Double> out = svc.pcvr(42L, List.of(1L, 2L, 3L));
        assertEquals(3, out.size(), "每个候选 item 一个 pCVR");
        for (double v : out.values()) {
            assertTrue(v >= 0.0 && v <= 1.0, "pCVR 必须∈[0,1]: " + v);
        }
    }

    @Test
    void degradation_missingModel_notReady_emptyMap() {
        AdProperties props = new AdProperties();
        props.getCvr().setModelPath("classpath:model/__nonexistent_dfm__.onnx");
        DfmCvrService svc = service(props);
        assertFalse(svc.isReady(), "模型缺失 → 未就绪");
        assertTrue(svc.pcvr(1L, List.of(1L, 2L)).isEmpty(), "未就绪 → 空 map(调用方保留复用头 pCVR)");
    }

    @Test
    void emptyOrNullCandidates_emptyMap() {
        DfmCvrService svc = service(new AdProperties());
        assertTrue(svc.pcvr(1L, List.of()).isEmpty());
        assertTrue(svc.pcvr(1L, null).isEmpty());
    }
}
