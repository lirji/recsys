package com.recsys.offline.persistence;

import com.recsys.offline.persistence.mapper.addb.UpliftSimulationMapper;
import com.recsys.persistence.spring.SpringMapperBatches;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 原分配/事实分别一次flush，避免17字段默认5000行变成超参数上限的单条SQL。 */
@Repository
public class UpliftSimulationWriter {
    private final SqlSessionTemplate owner;

    /** 复用广告自有库实际工厂，不新建连接池或事务。 */
    public UpliftSimulationWriter(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate owner) {
        this.owner = owner;
    }

    /** 保留原第一批的驱动提交进度，空输入无需连接。 */
    public void assignments(List<UpliftSimulationMapper.Assignment> rows) {
        if (rows.isEmpty()) return;
        SpringMapperBatches.execute(owner, UpliftSimulationMapper.class, mapper -> rows.forEach(mapper::insertAssignment));
    }

    /** 事实失败不撤销无外层事务时已经提交的分配。 */
    public void facts(List<UpliftSimulationMapper.Fact> rows) {
        if (rows.isEmpty()) return;
        SpringMapperBatches.execute(owner, UpliftSimulationMapper.class, mapper -> rows.forEach(mapper::insertFact));
    }
}
