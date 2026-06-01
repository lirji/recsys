package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 离线作业分发器。按 --job=<name> 触发对应作业。
 * 例:java -jar recsys-offline.jar --job=import-items
 *     mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=import-items
 */
@Component
public class JobRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final Map<String, OfflineJob> jobs;

    public JobRunner(List<OfflineJob> jobList) {
        this.jobs = jobList.stream().collect(
                java.util.stream.Collectors.toMap(OfflineJob::name, j -> j));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> jobNames = args.getOptionValues("job");
        if (jobNames == null || jobNames.isEmpty()) {
            log.info("未指定 --job;可用作业: {}", jobs.keySet());
            return;
        }
        for (String name : jobNames) {
            OfflineJob job = jobs.get(name);
            if (job == null) {
                log.error("未知作业: {};可用: {}", name, jobs.keySet());
                continue;
            }
            log.info("===== 开始作业: {} =====", name);
            long t0 = System.currentTimeMillis();
            job.run(args);
            log.info("===== 完成作业: {},耗时 {} ms =====", name, System.currentTimeMillis() - t0);
        }
    }
}
