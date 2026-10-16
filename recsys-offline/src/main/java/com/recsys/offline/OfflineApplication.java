package com.recsys.offline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 离线作业启动类。各作业以 CommandLineRunner / 带 --job= 参数的方式触发:
 *   import-items / backfill-embedding / itemcf / hot / user-embedding / gen-samples
 * 具体作业由 Track A/E 实现。
 */
@SpringBootApplication(scanBasePackages = "com.recsys")
public class OfflineApplication {
    /** 单独对账只装载RPC作业上下文，避免既有全量扫描初始化四路数据库和其他作业。 */
    public static void main(String[] args) {
        var requested = new DefaultApplicationArguments(args).getOptionValues("job");
        if (requested != null && requested.contains("billing-reconciliation")) {
            if (requested.size() != 1) throw new com.recsys.common.error.PublicErrorException(com.recsys.common.error.ErrorCode.JOB_INVALID_ARGUMENT);
            var application = new SpringApplication(BillingReconciliationCliConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (var context = application.run(args)) {
                // ApplicationRunner已经完成本次有界调用；及时关闭channel而不是遗留CLI进程。
            }
            return;
        }
        SpringApplication.run(OfflineApplication.class, args);
    }
}
