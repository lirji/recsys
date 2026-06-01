package com.recsys.offline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 离线作业启动类。各作业以 CommandLineRunner / 带 --job= 参数的方式触发:
 *   import-items / backfill-embedding / itemcf / hot / user-embedding / gen-samples
 * 具体作业由 Track A/E 实现。
 */
@SpringBootApplication(scanBasePackages = "com.recsys")
public class OfflineApplication {
    public static void main(String[] args) {
        SpringApplication.run(OfflineApplication.class, args);
    }
}
