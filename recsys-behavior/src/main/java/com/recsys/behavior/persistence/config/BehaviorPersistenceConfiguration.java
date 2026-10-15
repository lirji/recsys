package com.recsys.behavior.persistence.config;

import com.recsys.behavior.persistence.mapper.primary.BehaviorMapper;
import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 行为事实与自然回执共享原behavior主数据源，不引入其他owner的写入口。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScan(basePackageClasses = BehaviorMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class BehaviorPersistenceConfiguration { }
