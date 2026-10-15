package com.recsys.rank.persistence.config;

import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.rank.persistence.mapper.RatingSequenceMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 排序行为序列沿用宿主主源，不复用召回派生向量数据源。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScan(basePackageClasses = RatingSequenceMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class RankPersistenceConfiguration {
}
