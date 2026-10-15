package com.recsys.console.persistence.config;

import com.recsys.console.persistence.mapper.primary.UserProfileMapper;
import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 复用content宿主同一主源工厂，显式限定console Mapper扫描范围。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScan(basePackageClasses = UserProfileMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class ConsolePersistenceConfiguration { }
