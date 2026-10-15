package com.recsys.content;

import com.recsys.content.persistence.mapper.ItemMapper;
import com.recsys.content.persistence.model.CatalogTable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 本地副本物品服务:{@code findByIds}/{@code findById} 等读本地读模型 {@code item_local}(而非共享 {@code item})。
 * {@code recsys.content.item-source=replica} 时激活(默认 {@code db} → {@link MybatisContentService} 读 {@code item})——
 * item 物理搬到 content 自有库后,rec-serving 排序逐候选类目特征不再跨库直读。仅覆盖表名,SQL 全部继承。
 */
@Service
@ConditionalOnProperty(name = "recsys.content.item-source", havingValue = "replica")
public class ReplicaMybatisContentService extends MybatisContentService {

    public ReplicaMybatisContentService(ItemMapper items) {
        super(items);
    }

    @Override
    protected CatalogTable table() {
        return CatalogTable.REPLICA;
    }
}
