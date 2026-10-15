package com.recsys.ad.persistence.config;

import com.recsys.ad.persistence.mapper.addb.AdEmbeddingMapper;
import com.recsys.ad.persistence.mapper.derived.AdUserEmbeddingMapper;
import com.recsys.ad.persistence.mapper.sharded.AdCatalogMapper;
import com.recsys.persistence.spring.AdDbMybatisConfiguration;
import com.recsys.persistence.spring.PrimaryMybatisConfiguration;
import com.recsys.ad.persistence.mapper.primary.AdContractMapper;
import com.recsys.persistence.spring.DerivedMybatisConfiguration;
import com.recsys.persistence.spring.ShardedMybatisConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 每个Mapper包显式绑定逻辑数据源，不能依赖Primary意外选择广告数据。 */
@Configuration(proxyBeanMethods = false)
@Import({PrimaryMybatisConfiguration.class, AdDbMybatisConfiguration.class, DerivedMybatisConfiguration.class, ShardedMybatisConfiguration.class})
@MapperScans({
        @MapperScan(basePackageClasses = AdContractMapper.class, sqlSessionTemplateRef = "sqlSessionTemplate"),
        @MapperScan(basePackageClasses = AdEmbeddingMapper.class, sqlSessionTemplateRef = "adDbSqlSessionTemplate"),
        @MapperScan(basePackageClasses = AdUserEmbeddingMapper.class, sqlSessionTemplateRef = "derivedSqlSessionTemplate"),
        @MapperScan(basePackageClasses = AdCatalogMapper.class, sqlSessionTemplateRef = "shardedSqlSessionTemplate")
})
public class AdPersistenceConfiguration {
}
