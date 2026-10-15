package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import javax.sql.DataSource;
import java.time.Duration;

/** CPA安装开关默认关闭；已安装后停止新增与回收/恢复独立，不能遗留预算义务。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.conversions-enabled","recsys.ad.billing.conversion-settlement-enabled"},havingValue="true")
public class ConversionSettlementConfiguration {
    /** 只校验本owner的V013，不触发环境迁移。 */
    @Bean public ConversionSettlementRepository conversionSettlementRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions,ObjectMapper json) {
        var repository=new ConversionSettlementRepository(sessions,json); repository.verifySchema(); return repository;
    }
    /** 金额与业务事实共用adDb短事务，暂停新增独立于回收和查询。 */
    @Bean public ConversionSettlementService conversionSettlementService(ConversionSettlementRepository repository,ConversionQuoteRepository quotes,
            ReservationRepository accounts,DeliveryTokenCodec tokens,@Qualifier("adDbDataSource") DataSource ds,
            @Value("${recsys.ad.billing.transaction-timeout-seconds:5}") int timeout,
            @Value("${recsys.ad.billing.conversion-preparation-admission-enabled:true}") boolean preparations,
            @Value("${recsys.ad.billing.conversion-receipt-admission-enabled:true}") boolean receipts) {
        return new ConversionSettlementService(repository,quotes,accounts,tokens,BudgetConfiguration.transactions(ds,timeout),preparations,receipts);
    }
    /** 单线程fixed-delay唤醒，不堆积任务；数据库才是到期待办的权威。 */
    @Bean(destroyMethod="shutdown") public ThreadPoolTaskScheduler adConversionExpiryScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("ad-conversion-expiry-");
        scheduler.setRemoveOnCancelPolicy(true); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭新增不停止此任务；每轮最多100笔独立短事务。 */
    @Bean(initMethod="start",destroyMethod="stop") public ConversionExpiryJob conversionExpiryJob(ConversionSettlementService service,MeterRegistry metrics,
            @Qualifier("adConversionExpiryScheduler") ThreadPoolTaskScheduler scheduler,
            @Value("${recsys.ad.billing.expiry-interval-ms:1000}") long interval,
            @Value("${recsys.ad.billing.expiry-batch-size:100}") int batchSize) {
        if(interval<100 || interval>60000 || batchSize<1 || batchSize>100) throw new IllegalArgumentException("CPA回收间隔或批次越界");
        return new ConversionExpiryJob(service,metrics,scheduler,Duration.ofMillis(interval),batchSize);
    }
}
