package com.recsys.recengine.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdProperties;
import com.recsys.common.exposure.*;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.recengine.RecommendOrchestrator;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.exposure.NaturalDeliveryConfiguration;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** feed 默认关闭，复用自然显式政策和签名密钥，不能因存在旧配置自动接新流量。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="recsys.feed.delivery-enabled",havingValue="true")
@EnableConfigurationProperties(NaturalDeliveryConfiguration.Properties.class)
public class FeedDeliveryConfiguration {
    /** engine 主库持有整次操作，绝不与 ad owner 的资金数据源混用。 */
    @Bean public FeedDeliveryRepository feedDeliveryRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,ObjectMapper json) {
        var repository=new FeedDeliveryRepository(jdbc,json); repository.verifySchema(); return repository;
    }
    /** 两种广告模式共享编排；关闭新建仍允许恢复已经持久化的操作。 */
    @Bean public FeedDeliveryService feedDeliveryService(FeedDeliveryRepository repository,RecommendOrchestrator recommender,
            ContentGateway content,QueryUnderstandingService queries,ExperimentService experiments,AdServingGateway ads,AdProperties properties,
            NaturalDeliveryConfiguration.Properties natural,@Value("${recsys.feed.admission-enabled:true}") boolean admission) {
        var tokens=new NaturalQualificationToken(Objects.requireNonNull(natural.currentKeyId(),"必须配置自然签发 keyId"),natural.keys());
        return new FeedDeliveryService(repository,recommender,content,queries,experiments,ads,properties,tokens,
                NaturalQualification.approvedPolicy(natural.policy()),admission);
    }
}
