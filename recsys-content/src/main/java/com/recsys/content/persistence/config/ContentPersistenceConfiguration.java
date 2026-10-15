package com.recsys.content.persistence.config;

import com.recsys.content.persistence.mapper.ItemMapper;
import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 内容 Mapper 使用宿主默认源，多个领域库共同导入时复用同一个主源会话工厂。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScan(basePackageClasses = ItemMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class ContentPersistenceConfiguration {
}
