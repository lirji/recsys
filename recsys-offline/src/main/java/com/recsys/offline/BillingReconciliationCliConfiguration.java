package com.recsys.offline;

import net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;

/** 只由单独对账的main显式注册，避免被旧组件扫描识别为全局配置；不装载任何数据源。 */
@Import({BillingReconciliationConfiguration.class, BillingReconciliationJob.class, JobRunner.class})
@ImportAutoConfiguration(GrpcClientAutoConfiguration.class)
class BillingReconciliationCliConfiguration { }
