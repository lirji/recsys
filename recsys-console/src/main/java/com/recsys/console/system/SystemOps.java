package com.recsys.console.system;

import java.util.List;
import java.util.Map;

/**
 * 控制台运维只读快照:Redis 热配置 {@code recsys:tuning} + 离线作业 {@code job:status:*}。
 * Redis 不可用时 {@code redisAvailable=false},集合为空,不 500。
 */
public record SystemOps(
        boolean redisAvailable,
        String message,
        Map<String, String> tuning,
        List<JobStatus> jobs) {

    public record JobStatus(String name, String status, String updatedAt, String detail, String raw) {
    }
}
