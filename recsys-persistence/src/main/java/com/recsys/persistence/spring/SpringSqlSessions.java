package com.recsys.persistence.spring;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.recsys.persistence.MybatisSettings;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.util.Objects;

/** 只装配会话工厂，不创建连接池、不执行 SQL；事务资源始终来自宿主显式指定的数据源。 */
public final class SpringSqlSessions {

    private SpringSqlSessions() {
    }

    /** XML 按逻辑数据源隔离，防止 adDb 的 Mapper 被默认库工厂自动装载。 */
    public static SqlSessionFactory create(DataSource dataSource, String mapperPattern) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(Objects.requireNonNull(dataSource));
        factory.setConfiguration(MybatisSettings.create());
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources(mapperPattern));
        GlobalConfig global = new GlobalConfig();
        global.setBanner(false);
        factory.setGlobalConfig(global);
        return Objects.requireNonNull(factory.getObject());
    }
}
