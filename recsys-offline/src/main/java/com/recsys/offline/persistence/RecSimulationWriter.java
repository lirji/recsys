package com.recsys.offline.persistence;

import com.recsys.offline.persistence.mapper.primary.RecSimulationMapper;
import com.recsys.persistence.spring.SpringMapperBatches;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 默认至少五万条事件沿用原单个JDBC批次，不能改为超参数上限的一条VALUES SQL。 */
@Repository
public class RecSimulationWriter {
    private final SqlSessionTemplate owner;

    /** 复用实际主源工厂，外层事务与读取Mapper保持相同连接归属。 */
    public RecSimulationWriter(@Qualifier("sqlSessionTemplate") SqlSessionTemplate owner) {
        this.owner = owner;
    }

    /** 原事件顺序逐条绑定、一次flush；空列表不取得连接，不引入新事务。 */
    public void insert(List<RecSimulationMapper.Event> rows) {
        if (rows.isEmpty()) return;
        SpringMapperBatches.execute(owner, RecSimulationMapper.class, mapper -> rows.forEach(mapper::insert));
    }
}
