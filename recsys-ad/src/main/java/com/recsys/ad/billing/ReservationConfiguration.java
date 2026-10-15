package com.recsys.ad.billing;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.time.Duration;

/** 预占与回收默认仅在 sandbox 装配；停止新增 admission 不停止在途回收。 */
@org.springframework.context.annotation.Conditional(SandboxOwnerCondition.class)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recsys.ad.billing.sandbox-enabled", havingValue = "true")
public class ReservationConfiguration {
    /** V003 不存在时拒绝启用，不能创建没有恢复索引/约束的半套预占能力。 */
    @Bean
    public ReservationRepository reservationRepository(@Qualifier("adDbJdbc") JdbcTemplate jdbc) {
        var repository = new ReservationRepository(jdbc);
        repository.verifySchema();
        return repository;
    }

    /** 引用同一 adDb 事务与报价资源，故障时整笔提交或整笔回滚。 */
    @Bean
    public ReservationService reservationService(ReservationRepository repository, CpmQuoteRepository quotes,
            @Qualifier("adDbDataSource") DataSource dataSource,
            @Value("${recsys.ad.billing.transaction-timeout-seconds:5}") int timeoutSeconds,
            @Value("${recsys.ad.billing.reservation-admission-enabled:true}") boolean admissionEnabled) {
        return new ReservationService(repository, quotes, BudgetConfiguration.transactions(dataSource, timeoutSeconds), admissionEnabled);
    }

    /** 独立一个周期任务和单线程，不启用全局注解扫描或影响其他模块的定时任务。 */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler adReservationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ad-reservation-expiry-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /** 初始化时只注册一个 fixed-delay tick，不提前连接数据库或开启额外线程池。 */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public ReservationExpiryJob reservationExpiryJob(ReservationService service, MeterRegistry metrics,
            @Qualifier("adReservationScheduler") ThreadPoolTaskScheduler scheduler,
            @Value("${recsys.ad.billing.expiry-interval-ms:1000}") long intervalMs,
            @Value("${recsys.ad.billing.expiry-batch-size:100}") int batchSize) {
        if (intervalMs < 100 || intervalMs > 60000 || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("回收间隔须为 100～60000ms，单轮批次须为 1～100");
        }
        return new ReservationExpiryJob(service, metrics, scheduler, Duration.ofMillis(intervalMs), batchSize);
    }
}
