package com.recsys.advertiser.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 细粒度归属判权开关(recsys.authz.*,接统一权限平台 auth-platform)。
 * mode 三态:disabled(默认,零行为变化)/ shadow(判但只记 would-deny 日志)/ enforce(真拦截)。
 * 判权服务地址走 SDK 自己的 {@code authz.client.*}(见 application.yml)。
 */
@ConfigurationProperties(prefix = "recsys.authz")
public class AuthzProperties {

    private String mode = "disabled";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
