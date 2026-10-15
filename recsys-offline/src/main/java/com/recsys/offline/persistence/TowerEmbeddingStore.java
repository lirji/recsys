package com.recsys.offline.persistence;

import com.recsys.offline.persistence.mapper.derived.TowerEmbeddingMapper;
import com.recsys.persistence.spring.SpringMapperBatches;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 双塔导入的bootstrap与批处理适配器，复用同一个实际派生源，不增加事务。 */
@Repository
public class TowerEmbeddingStore {
    private final SqlSessionTemplate owner;
    private final TowerEmbeddingMapper mapper;

    /** 工厂内的真实DataSource同时用于DDL和Mapper，避免同URL不同事务资源。 */
    public TowerEmbeddingStore(@Qualifier("derivedSqlSessionTemplate") SqlSessionTemplate owner) {
        this.owner = owner;
        this.mapper = owner.getMapper(TowerEmbeddingMapper.class);
    }

    /** 固定64维建表后清表；文件缺失判断必须在调用本方法前完成。 */
    public void rebuild() {
        var dataSource = owner.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/item-tower-embedding.sql")).execute(dataSource);
        mapper.truncateAll();
    }

    /** 原500行逻辑批次一次flush；成功计数保持原JDBC更新数组长度，包含重复ID。 */
    public int writeBatch(List<TowerEmbeddingMapper.Row> rows) {
        if (rows.isEmpty()) return 0;
        SpringMapperBatches.execute(owner, TowerEmbeddingMapper.class, mapper -> rows.forEach(mapper::upsert));
        return rows.size();
    }

    /** 保持原结束统计，不用处理行数冒充最终唯一物品数。 */
    public Long count() { return mapper.countAll(); }
}
