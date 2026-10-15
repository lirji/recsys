package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdProperties;
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

/** 点击安装开关默认关闭；已安装后停止新增与回收/恢复独立，不能遗留预算义务。 */
@Configuration(proxyBeanMethods=false)
@Conditional(SandboxOwnerCondition.class)
@ConditionalOnProperty(name={"recsys.ad.billing.sandbox-enabled","recsys.ad.billing.clicks-enabled","recsys.ad.billing.click-settlement-enabled"},havingValue="true")
public class ClickSettlementConfiguration {
    /** 只校验本owner的V009，不触发环境迁移。 */
    @Bean public ClickSettlementRepository clickSettlementRepository(@Qualifier("adDbSqlSessionTemplate") SqlSessionTemplate sessions,ObjectMapper json) {
        var repository=new ClickSettlementRepository(sessions,json); repository.verifySchema(); return repository;
    }
    /** 所有金额和反作弊效果复用adDb短事务；策略在启动时复制校验。 */
    @Bean public ClickSettlementService clickSettlementService(ClickSettlementRepository repository,ClickQuoteRepository quotes,
            ReservationRepository accounts,DeliveryTokenCodec tokens,@Qualifier("adDbDataSource") DataSource ds,AdProperties properties,
            @Value("${recsys.ad.billing.transaction-timeout-seconds:5}") int timeout,
            @Value("${recsys.ad.billing.click-preparation-admission-enabled:true}") boolean preparations,
            @Value("${recsys.ad.billing.click-receipt-admission-enabled:true}") boolean receipts) {
        var fraud=properties.getAntiFraud();
        return new ClickSettlementService(repository,quotes,accounts,tokens,BudgetConfiguration.transactions(ds,timeout),preparations,receipts,
                new ClickSettlementService.FraudPolicy(fraud.isEnabled(),fraud.getMaxClicksPerMinute()));
    }
    /** 单线程fixed-delay唤醒，不堆积任务；数据库才是到期待办的权威。 */
    @Bean(destroyMethod="shutdown") public ThreadPoolTaskScheduler adClickExpiryScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("ad-click-expiry-");
        scheduler.setRemoveOnCancelPolicy(true); scheduler.setWaitForTasksToCompleteOnShutdown(false); return scheduler;
    }
    /** 关闭新增不停止此任务；每轮最多100笔独立短事务。 */
    @Bean(initMethod="start",destroyMethod="stop") public ClickExpiryJob clickExpiryJob(ClickSettlementService service,MeterRegistry metrics,
            @Qualifier("adClickExpiryScheduler") ThreadPoolTaskScheduler scheduler,
            @Value("${recsys.ad.billing.expiry-interval-ms:1000}") long interval,
            @Value("${recsys.ad.billing.expiry-batch-size:100}") int batchSize) {
        if(interval<100 || interval>60000 || batchSize<1 || batchSize>100) throw new IllegalArgumentException("点击回收间隔或批次越界");
        return new ClickExpiryJob(service,metrics,scheduler,Duration.ofMillis(interval),batchSize);
    }
}
