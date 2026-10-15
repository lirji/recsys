package com.recsys.offline.persistence;

import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.mapper.addb.SeedAdVectorMapper;
import com.recsys.offline.persistence.mapper.derived.SeedItemVectorMapper;
import com.recsys.offline.persistence.mapper.sharded.SeedAdMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.context.annotation.Configuration;

/** 四个逻辑来源显式绑定对应会话；按需源库Mapper始终不进入宿主扫描。 */
@Configuration
@MapperScans({
        @MapperScan(basePackageClasses=PositiveFeedbackMapper.class, sqlSessionTemplateRef="sqlSessionTemplate"),
        @MapperScan(basePackageClasses=SeedAdVectorMapper.class, sqlSessionTemplateRef="adDbSqlSessionTemplate"),
        @MapperScan(basePackageClasses=SeedItemVectorMapper.class, sqlSessionTemplateRef="derivedSqlSessionTemplate"),
        @MapperScan(basePackageClasses=SeedAdMapper.class, sqlSessionTemplateRef="shardedSqlSessionTemplate")
})
public class OfflinePersistenceConfiguration { }
