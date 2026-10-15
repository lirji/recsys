package com.recsys.offline.persistence;

import com.recsys.offline.persistence.mapper.addb.AdSimulationMapper;
import com.recsys.persistence.spring.SpringMapperBatches;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import java.util.List;

/** 三类原JDBC批次各自flush，保留无外层事务时的驱动提交进度，不引入新事务。 */
@Repository
public class AdSimulationWriter {
    private final SqlSessionTemplate owner;

    /** 复用实际广告自有库会话工厂，不能新建同URL连接池替代事务资源。 */
    public AdSimulationWriter(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate owner) {
        this.owner = owner;
    }

    /** 整个曝光列表沿用一次driver.executeBatch，空输入不取得连接。 */
    public void impressions(List<AdSimulationMapper.Impression> rows) {
        if (rows.isEmpty()) return;
        SpringMapperBatches.execute(owner, AdSimulationMapper.class, mapper -> rows.forEach(mapper::insertImpression));
    }

    /** 点击仍在曝光批次之后单独执行，失败不伪装成整作业原子回滚。 */
    public void clicks(List<AdSimulationMapper.Action> rows) {
        if (rows.isEmpty()) return;
        SpringMapperBatches.execute(owner, AdSimulationMapper.class, mapper -> rows.forEach(mapper::insertClick));
    }

    /** 转化仍保留独立第三批，外层事务存在时三批统一由调用方提交/回滚。 */
    public void conversions(List<AdSimulationMapper.Action> rows) {
        if (rows.isEmpty()) return;
        SpringMapperBatches.execute(owner, AdSimulationMapper.class, mapper -> rows.forEach(mapper::insertConversion));
    }
}
