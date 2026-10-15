package com.recsys.recall.persistence.config;

import com.recsys.persistence.spring.DerivedMybatisConfiguration;
import com.recsys.recall.persistence.mapper.derived.EmbeddingMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 派生库Mapper只能由专用会话工厂装配，不依赖默认注入优先级。 */
@Configuration(proxyBeanMethods = false)
@Import(DerivedMybatisConfiguration.class)
@MapperScan(basePackageClasses = EmbeddingMapper.class, sqlSessionTemplateRef = "derivedSqlSessionTemplate")
public class RecallDerivedPersistenceConfiguration {
}
