package com.recsys.persistence.spring;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 派生读模型复用宿主原数据源，独立扫描防止向量查询误走主库或分片解析器。 */
@Configuration(proxyBeanMethods = false)
public class DerivedMybatisConfiguration {

    /** 数据源身份保持不变，后续事务也可复用同一个Spring连接资源。 */
    @Bean
    public SqlSessionFactory derivedSqlSessionFactory(@Qualifier("derivedDbDataSource") DataSource dataSource) throws Exception {
        return SpringSqlSessions.create(dataSource, "classpath*:mapper/*/derived/**/*.xml");
    }

    /** 代理负责会话关闭及异常转换，调用方不持有跨请求会话。 */
    @Bean
    public SqlSessionTemplate derivedSqlSessionTemplate(@Qualifier("derivedSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }
}
