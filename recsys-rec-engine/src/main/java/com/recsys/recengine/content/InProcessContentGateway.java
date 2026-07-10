package com.recsys.recengine.content;

import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 默认(单体回退)实现:进程内直调 {@link ContentService}(recsys-content lib)。行为与拆分前等价、零网络跳。
 * {@code recsys.content.serving.mode} 缺省或 = {@code in-process} 时生效(绞杀者迁移的安全默认与回滚落点)。
 */
@Component
@ConditionalOnProperty(name = "recsys.content.serving.mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessContentGateway implements ContentGateway {

    private final ContentService content;

    public InProcessContentGateway(ContentService content) {
        this.content = content;
    }

    @Override
    public Map<Long, Item> findByIds(List<Long> itemIds) {
        return content.findByIds(itemIds);
    }
}
