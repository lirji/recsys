package com.recsys.recengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 推荐编排服务启动类(对外主入口)。
 *
 * 单体起步:scanBasePackages 覆盖 com.recsys,使各计算/领域库(recall/rank/feature/
 * embedding/content/user)中的 @Component/@RestController 被统一扫描装配。
 * 拆分为微服务时,改为只扫描本模块包 + 远程调用各服务。
 */
@SpringBootApplication(scanBasePackages = "com.recsys")
public class RecEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecEngineApplication.class, args);
    }
}
