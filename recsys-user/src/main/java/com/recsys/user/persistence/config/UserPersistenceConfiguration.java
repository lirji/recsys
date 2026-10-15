package com.recsys.user.persistence.config;

import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.user.persistence.mapper.UserProfileMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 只扫描本领域数据库 Mapper；协议转换器和其他数据源不会被误绑定到用户主库。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScan(basePackageClasses = UserProfileMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate")
public class UserPersistenceConfiguration {
}
