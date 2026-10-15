package com.recsys.adserving.persistence.config;

import com.recsys.adserving.persistence.mapper.addb.AdServableMapper;
import com.recsys.persistence.spring.AdDbMybatisConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 目录副本与报表属于广告自有库，不进入分片源或推荐主库。 */
@Configuration(proxyBeanMethods = false)
@Import(AdDbMybatisConfiguration.class)
@MapperScan(basePackageClasses = AdServableMapper.class, sqlSessionTemplateRef = "adDbSqlSessionTemplate")
public class AdServingPersistenceConfiguration { }
