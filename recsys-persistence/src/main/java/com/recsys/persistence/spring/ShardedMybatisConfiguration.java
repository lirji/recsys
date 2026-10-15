package com.recsys.persistence.spring;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** 广告目录会话复用ShardingSphere逻辑数据源，保留原路由与主键生成。 */
@Configuration(proxyBeanMethods = false)
public class ShardedMybatisConfiguration {

    /** XML按逻辑数据源隔离，不使用跨源的全包扫描。 */
    @Bean
    public SqlSessionFactory shardedSqlSessionFactory(@Qualifier("adShardingDataSource") DataSource dataSource) throws Exception {
        return SpringSqlSessions.create(dataSource, "classpath*:mapper/*/sharded/**/*.xml");
    }

    /** 每次调用通过Spring代理管理会话关闭与异常转换。 */
    @Bean
    public SqlSessionTemplate shardedSqlSessionTemplate(@Qualifier("shardedSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }
}
