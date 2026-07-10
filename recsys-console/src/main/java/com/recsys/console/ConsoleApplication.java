package com.recsys.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 控制台后端(console-api)。前后端分离后,本模块不再托管前端静态资源,
 * 只对外提供控制台 BFF 接口(当前:离线报表读取 /api/console/report/**)。
 * 前端已独立为仓库根 console/ 的 Vite 工程,由 nginx 同源托管、经网关访问。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
