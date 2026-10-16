package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.persistence.mapper.primary.*;
import com.recsys.advertiser.persistence.mapper.derived.AdvertiserItemEmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.time.Duration;

/** 默认关闭时不连接治理表、不建立worker/producer/Admin或自动迁移；所有运行能力显式装配。 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogRuntimeConfiguration {
    @Bean public InitializingBean catalogSwitchGuard(CatalogProperties p,Environment env) {
        return ()->{
            if(p.any() && !p.enabled())throw new IllegalStateException("目录子开关需要enabled");
            if(!p.enabled())return;
            if(!"jdbc:shardingsphere:classpath:sharding-catalog-reliable.yaml?placeholder-type=environment".equals(env.getProperty("spring.datasource.url")))
                throw new IllegalStateException("可靠目录必须选择显式可靠分片资源");
            if(env.getProperty("recsys.ad.catalog.publish-events",Boolean.class,false))throw new IllegalStateException("可靠目录不能同时启用旧目录事件");
            if(p.sourceId()==null || p.writerName()==null || p.writerName().isBlank() || p.writerName().length()>100
                    || p.writerId()==null || p.writerId()<0 || p.writerId()>1023
                    || !p.writerId().toString().equals(env.getProperty("CATALOG_WRITER_ID")))throw new IllegalStateException("可靠目录需要显式源和唯一writer登记");
            // 除profile关闭实际SQL外，禁止MyBatis调试参数输出；默认关闭路径不会执行此日志变更。
            var logging=org.springframework.boot.logging.LoggingSystem.get(getClass().getClassLoader());
            logging.setLogLevel("ShardingSphere-SQL",org.springframework.boot.logging.LogLevel.OFF);
            logging.setLogLevel("com.recsys.advertiser.persistence.mapper",org.springframework.boot.logging.LogLevel.OFF);
        };
    }

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="recsys.ad.catalog.reliability.enabled",havingValue="true")
    static class Enabled {
        @Bean @DependsOn("catalogSwitchGuard") CatalogRuntimeBinding catalogBinding(CatalogProperties p,CatalogLocalMapper mapper,ObjectMapper json){return new CatalogRuntimeBinding(p,mapper,json);}
        @Bean CatalogJson catalogJson(ObjectMapper json){return new CatalogJson(json);}
        @Bean CatalogSnapshotCodec catalogCodec(ObjectMapper json){return new CatalogSnapshotCodec(json);}
        @Bean CatalogLocalTransactions catalogTransactions(DataSource source,CatalogLocalMapper mapper,CatalogRuntimeBinding binding){return new CatalogLocalTransactions(source,mapper);}
        @Bean CatalogCommandRepository catalogCommands(CatalogLocalMapper mapper,CatalogLocalTransactions tx){return new CatalogCommandRepository(mapper,tx);}
        @Bean CatalogStepRepository catalogSteps(CatalogLocalMapper mapper,CatalogLocalTransactions tx,ObjectMapper json){return new CatalogStepRepository(mapper,tx,json);}
        @Bean CatalogOutboxRepository catalogOutbox(CatalogLocalMapper mapper,CatalogLocalTransactions tx,CatalogSnapshotCodec codec){return new CatalogOutboxRepository(mapper,tx,codec);}
        @Bean CatalogBusinessSteps catalogBusinessSteps(CatalogBusinessMapper business,CatalogLocalMapper local,CatalogStepRepository steps,CatalogCommandRepository commands,CatalogOutboxRepository outbox,CatalogJson json,CatalogSnapshotCodec codec){return new CatalogBusinessSteps(business,local,steps,commands,outbox,json,codec);}
        @Bean(destroyMethod="close") CatalogEnrichment catalogEnrichment(Environment env,StringRedisTemplate redis) throws Exception {
            // 派生读取沿既有库身份但使用独立有界连接；不把该DataSource注册成Boot主源。
            String db=env.getProperty("DERIVED_PG_DB",env.getProperty("PG_DB","recsys"));
            String url="jdbc:postgresql://"+env.getProperty("PG_HOST","localhost")+":"+env.getProperty("PG_PORT","5432")+"/"+db;
            var ds=new DriverManagerDataSource(url,env.getProperty("PG_USER","recsys"),env.getProperty("PG_PASSWORD","recsys"));
            var settings=new java.util.Properties();settings.setProperty("connectTimeout","1");settings.setProperty("socketTimeout","1");ds.setConnectionProperties(settings);
            var factory=SpringSqlSessions.create(ds,"classpath*:mapper/advertiser/derived/AdvertiserItemEmbeddingMapper.xml");
            factory.getConfiguration().setDefaultStatementTimeout(1);
            return new CatalogEnrichment(new SqlSessionTemplate(factory).getMapper(AdvertiserItemEmbeddingMapper.class),redis);
        }
        @Bean CatalogCommandService catalogService(CatalogCommandRepository commands,CatalogBusinessSteps steps,CatalogEnrichment enrichment,CatalogJson json,AdvertiserAuthz authz,Environment env) {
            var bounded=new com.lrj.authz.sdk.RemoteAuthzEngine(env.getProperty("authz.client.server-url","http://localhost:8200"),env.getProperty("authz.client.token",""),Duration.ofSeconds(1),Duration.ofSeconds(3));
            return new CatalogCommandService(commands,steps,enrichment,json,authz,bounded);
        }
        @Bean CatalogWriteRouter catalogRouter(CatalogProperties p,CatalogCommandRepository commands,CatalogCommandService service,CatalogBusinessMapper business,CatalogJson json,AdvertiserAuthz authz){return new CatalogWriteRouter(p.sourceId(),p.admissionEnabled(),commands,service,business,json,authz);}
        @Bean CatalogCommandQuery catalogQuery(CatalogLocalMapper mapper){return new CatalogCommandQuery(mapper);}
        @Bean(initMethod="start",destroyMethod="close") CatalogMetrics catalogMetrics(CatalogLocalMapper mapper,CatalogJson json,io.micrometer.core.instrument.MeterRegistry registry){return new CatalogMetrics(mapper,json,registry);}
        @Bean @ConditionalOnProperty(name="recsys.ad.catalog.reliability.recovery-enabled",havingValue="true")
        CatalogShardRecheckService catalogShardRecheck(CatalogLocalMapper local,CatalogRecheckMapper checks,CatalogLocalTransactions tx,
                CatalogRuntimeBinding binding,CatalogJson json,CatalogSnapshotCodec codec,CatalogProperties p,Environment env) {
            return new CatalogShardRecheckService(local,checks,tx,binding,json,codec,p.sourceId(),env.getRequiredProperty("spring.kafka.bootstrap-servers"));
        }
        @Bean @ConditionalOnProperty(name="recsys.ad.catalog.reliability.recovery-enabled",havingValue="true")
        CatalogRecoveryService catalogRecovery(CatalogLocalMapper local,CatalogRecoveryMapper recovery,CatalogLocalTransactions tx,
                CatalogRuntimeBinding binding,CatalogJson json,CatalogSnapshotCodec codec,CatalogProperties p,Environment env) {
            return new CatalogRecoveryService(local,recovery,tx,binding,json,codec,p.sourceId(),env.getRequiredProperty("spring.kafka.bootstrap-servers"));
        }
        @Bean @ConditionalOnProperty(name="recsys.ad.catalog.reliability.recovery-enabled",havingValue="true")
        CatalogInitializationService catalogInitialization(CatalogLocalMapper local,CatalogInitializationMapper init,CatalogBusinessMapper business,
                CatalogLocalTransactions tx,CatalogBusinessSteps steps,CatalogOutboxRepository outbox,CatalogEnrichment enrichment,
                CatalogRuntimeBinding binding,CatalogJson json,CatalogSnapshotCodec codec,CatalogProperties p,Environment env) {
            return new CatalogInitializationService(local,init,business,tx,steps,outbox,enrichment,binding,json,codec,p.sourceId(),env.getRequiredProperty("spring.kafka.bootstrap-servers"));
        }
        @Bean(destroyMethod="close") @ConditionalOnProperty(name="recsys.ad.catalog.reliability.relay-enabled",havingValue="true")
        CatalogKafkaPublisher catalogPublisher(CatalogRuntimeBinding binding,Environment env) {
            var bound=binding.verify(true).getFirst();return new CatalogKafkaPublisher(env.getRequiredProperty("spring.kafka.bootstrap-servers"),bound.clusterId(),bound.topicId());
        }
        @Bean @ConditionalOnProperty(name="recsys.ad.catalog.reliability.relay-enabled",havingValue="true")
        CatalogRelay catalogRelay(CatalogOutboxRepository outbox,CatalogKafkaPublisher publisher,CatalogProperties p){return new CatalogRelay(outbox,publisher,p.sourceId());}
        @Bean(initMethod="start",destroyMethod="close")
        @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${recsys.ad.catalog.reliability.worker-enabled:false} or ${recsys.ad.catalog.reliability.relay-enabled:false}")
        CatalogWorker catalogWorker(CatalogProperties p,CatalogLocalMapper mapper,CatalogCommandService commands,CatalogStepRepository steps,
                org.springframework.beans.factory.ObjectProvider<CatalogRelay> relay,CatalogRuntimeBinding binding) {
            binding.verify(true);return new CatalogWorker(mapper,p.workerEnabled()?commands:null,steps,relay.getIfAvailable(),p.sourceId());
        }
    }
}
