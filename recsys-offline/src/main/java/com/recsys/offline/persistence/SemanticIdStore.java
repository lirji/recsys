package com.recsys.offline.persistence;

import com.recsys.offline.persistence.mapper.derived.SemanticIdMapper;
import com.recsys.persistence.spring.SpringMapperBatches;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 语义ID导入的bootstrap与批处理适配，复用实际派生源及原事务边界。 */
@Repository
public class SemanticIdStore {
    private final SqlSessionTemplate owner;
    private final SemanticIdMapper mapper;

    /** Mapper与DDL从同一factory取资源，禁止另建同URL连接池。 */
    public SemanticIdStore(@Qualifier("derivedSqlSessionTemplate") SqlSessionTemplate owner) {
        this.owner = owner;
        this.mapper = owner.getMapper(SemanticIdMapper.class);
    }

    /** 保留原建表/前缀索引后清表顺序，不改变缺文件时不调用的规则。 */
    public void rebuild() {
        var dataSource = owner.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/item-semantic-id.sql")).execute(dataSource);
        mapper.truncateAll();
    }

    /** 每个原500行批次一次flush，同批重复ID仍按输入顺序覆盖。 */
    public int writeBatch(List<SemanticIdMapper.Row> rows) {
        if (rows.isEmpty()) return 0;
        SpringMapperBatches.execute(owner, SemanticIdMapper.class, mapper -> rows.forEach(mapper::upsert));
        return rows.size();
    }

    /** 保留结束时独立查表统计，不能将重复输入计作唯一物品。 */
    public Long count() { return mapper.countAll(); }
}
