package com.recsys.advertiser.persistence.config;

import com.recsys.advertiser.persistence.mapper.primary.AdvertiserMapper;
import com.recsys.advertiser.persistence.mapper.derived.AdvertiserItemEmbeddingMapper;
import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 主分片源和派生源明确隔离，保持原Boot数据源自动配置。 */
@Configuration(proxyBeanMethods = false)
@Import(PrimaryMybatisConfiguration.class)
@MapperScans({
        @MapperScan(basePackageClasses = AdvertiserMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate"),
        @MapperScan(basePackageClasses = AdvertiserItemEmbeddingMapper.class, sqlSessionTemplateRef = "derivedSqlSessionTemplate")
})
public class AdvertiserPersistenceConfiguration {
    /** 代理负责会话关闭和Spring事务连接的复用。 */
    @Bean
    public SqlSessionTemplate derivedSqlSessionTemplate(@Qualifier("derivedSqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }
}
