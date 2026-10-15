package com.recsys.query.persistence.config;

import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.query.persistence.mapper.LexemeMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 查询库复用宿主主数据源，不为英文分词额外建立连接池。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScan(basePackageClasses = LexemeMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class QueryPersistenceConfiguration {
}
