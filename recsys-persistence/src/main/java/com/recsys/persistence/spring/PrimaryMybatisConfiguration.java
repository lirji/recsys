package com.recsys.persistence.spring;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/** 各业务库通过 Import 复用默认源工厂；连接池、连接参数与事务管理仍归宿主所有。 */
@Configuration(proxyBeanMethods = false)
public class PrimaryMybatisConfiguration {

    /** 只加载 primary 目录，不让主库自动吸收分片、账务或向量源的语句。 */
    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource) throws Exception {
        return SpringSqlSessions.create(dataSource, "classpath*:mapper/*/primary/**/*.xml");
    }

    /** 复用 Spring 事务绑定的连接；业务调用方不得自行 openSession/commit。 */
    @Bean
    @Primary
    public SqlSessionTemplate sqlSessionTemplate(@Qualifier("sqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }
}
