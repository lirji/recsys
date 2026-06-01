package com.recsys.offline;

import org.springframework.boot.ApplicationArguments;

/**
 * 离线作业接口。每个作业实现一个,由 JobRunner 按 name 分发。
 */
public interface OfflineJob {

    /** 作业名,对应 --job=<name>。 */
    String name();

    void run(ApplicationArguments args) throws Exception;
}
