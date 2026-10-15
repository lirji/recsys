package com.recsys.recall.persistence.config;

import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.recall.persistence.mapper.primary.RecallInputMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 只扫描主源Mapper包，避免将后续派生库查询绑定到默认库。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScan(basePackageClasses = RecallInputMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class RecallPersistenceConfiguration {
}
