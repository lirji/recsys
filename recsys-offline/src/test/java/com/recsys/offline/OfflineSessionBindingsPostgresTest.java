package com.recsys.offline;

import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 四个隔离schema证明会话资源身份和XML隔离；不把普通PG当作分片路由验收。 */
@Testcontainers
class OfflineSessionBindingsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static final Map<String,DataSource> SOURCES=new LinkedHashMap<>();
    static final Map<String,String> SOURCE_BEANS=Map.of("primary","dataSource","addb","adDbDataSource",
            "derived","derivedDbDataSource","sharded","adShardingDataSource");
    static final Map<String,String> SESSION_BEANS=Map.of("primary","sqlSessionTemplate","addb","adDbSqlSessionTemplate",
            "derived","derivedSqlSessionTemplate","sharded","shardedSqlSessionTemplate");

    @BeforeAll static void prepareSources() {
        var admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        for(String source:SOURCE_BEANS.keySet()) {
            admin.execute("CREATE SCHEMA offline_"+source);
            String url=PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"currentSchema=offline_"+source;
            SOURCES.put(source,new DriverManagerDataSource(url,PG.getUsername(),PG.getPassword()));
        }
    }

    /** 使用宿主实际Import清单，但隔离环境数据源，绝不读取本机或共享库凭据。 */
    @Test void fourSessionsUseOriginalOwnersAndOnlyTheirOwnXml() {
        var imports=OfflineAdShardingConfig.class.getAnnotation(Import.class);
        assertNotNull(imports);
        // 宿主Import已包含实际四路Mapper扫描配置，无需在测试中复制扫描规则。
        var runner = new ApplicationContextRunner().withUserConfiguration(imports.value())
                .withUserConfiguration(com.recsys.offline.persistence.R9EmbeddingStore.class);
        for(var source:SOURCES.entrySet()) {
            runner=runner.withBean(SOURCE_BEANS.get(source.getKey()),DataSource.class,source::getValue);
        }
        runner.run(context->{
            assertNull(context.getStartupFailure());
            assertEquals(4,context.getBeansOfType(SqlSessionTemplate.class).size());
            assertNotNull(context.getBean(PositiveFeedbackMapper.class));
            assertNotNull(context.getBean(com.recsys.offline.persistence.R9EmbeddingStore.class));
            assertEquals(4,context.getBeansOfType(DataSource.class).size());
            assertTrue(context.getBeansOfType(com.recsys.offline.persistence.mapper.manual.R9EmbeddingMapper.class).isEmpty());
            var feedbackFactory = context.getBean("&positiveFeedbackMapper", MapperFactoryBean.class);
            assertSame(context.getBean("sqlSessionTemplate"), feedbackFactory.getSqlSession());
            for (var binding : Map.ofEntries(
                    Map.entry("seedAdMapper", "shardedSqlSessionTemplate"),
                    Map.entry("seedAdVectorMapper", "adDbSqlSessionTemplate"),
                    Map.entry("seedItemVectorMapper", "derivedSqlSessionTemplate"),
                    Map.entry("lookalikeMapper", "derivedSqlSessionTemplate"),
                    Map.entry("hotScoreMapper", "sqlSessionTemplate"),
                    Map.entry("coldBanditMapper", "sqlSessionTemplate"),
                    Map.entry("banditImpressionMapper", "sqlSessionTemplate"),
                    Map.entry("idfCorpusMapper", "sqlSessionTemplate"),
                    Map.entry("featureStatsMapper", "sqlSessionTemplate"),
                    Map.entry("trainingCatalogMapper", "sqlSessionTemplate"),
                    Map.entry("trainingSampleMapper", "sqlSessionTemplate")).entrySet()) {
                var factory = context.getBean("&" + binding.getKey(), MapperFactoryBean.class);
                assertSame(context.getBean(binding.getValue()), factory.getSqlSession());
            }
            for(var source:SOURCES.entrySet()) {
                var sessions=context.getBean(SESSION_BEANS.get(source.getKey()),SqlSessionTemplate.class);
                var config=sessions.getSqlSessionFactory().getConfiguration();
                assertSame(source.getValue(),config.getEnvironment().getDataSource());
                assertFalse(config.hasMapper(com.recsys.offline.persistence.mapper.manual.R9EmbeddingMapper.class));
                assertFalse(config.isCacheEnabled());assertEquals(LocalCacheScope.STATEMENT,config.getLocalCacheScope());
                for(String other:SOURCES.keySet()) {
                    String statement="offline.probe."+other+".schema";
                    assertEquals(source.getKey().equals(other),config.hasStatement(statement));
                }
                assertEquals("offline_"+source.getKey(),sessions.selectOne("offline.probe."+source.getKey()+".schema"));
            }
        });
    }
}
