package com.recsys.persistence.spring;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 广告自有库会话复用原数据源，资金事务资源身份不得变化。 */
@Configuration(proxyBeanMethods = false)
public class AdDbMybatisConfiguration {

    /** XML按逻辑数据源隔离，不使用跨源的全包扫描。 */
    @Bean
    public SqlSessionFactory adDbSqlSessionFactory(@Qualifier("adDbDataSource") DataSource dataSource) throws Exception {
        return SpringSqlSessions.create(dataSource, "classpath*:mapper/*/addb/**/*.xml");
    }

    /** 每次调用通过Spring代理管理会话关闭与异常转换。 */
    @Bean
    public SqlSessionTemplate adDbSqlSessionTemplate(@Qualifier("adDbSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }
}
