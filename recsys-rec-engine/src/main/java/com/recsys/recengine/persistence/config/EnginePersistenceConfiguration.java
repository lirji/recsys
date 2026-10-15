package com.recsys.recengine.persistence.config;

import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.persistence.spring.DerivedMybatisConfiguration;
import com.recsys.recengine.persistence.mapper.primary.EngineBehaviorMapper;
import com.recsys.recengine.persistence.mapper.derived.EngineEmbeddingMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 主源行为与派生向量各用原宿主DataSource，会话工厂与其他领域配置共享。 */
@Configuration(proxyBeanMethods = false)
@Import({PrimaryMybatisConfiguration.class,DerivedMybatisConfiguration.class})
@MapperScans({
        @MapperScan(basePackageClasses = EngineBehaviorMapper.class,sqlSessionTemplateRef = "sqlSessionTemplate"),
        @MapperScan(basePackageClasses = EngineEmbeddingMapper.class,sqlSessionTemplateRef = "derivedSqlSessionTemplate")
})
public class EnginePersistenceConfiguration { }
