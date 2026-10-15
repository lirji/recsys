package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
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

    /** 非成功结果必须向上传播，调度调用方不能把失败当作完成。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> jobNames = args.getOptionValues("job");
        if (!args.containsOption("job")) {
            log.info("未指定 --job;可用作业: {}", jobs.keySet());
            return;
        }
        // 先核对整个请求，避免已运行前面的作业后才发现后面的名称不存在。
        if (jobNames == null || jobNames.isEmpty() || jobNames.stream().anyMatch(n -> !jobs.containsKey(n))) {
            throw new PublicErrorException(ErrorCode.JOB_UNKNOWN);
        }
        for (String name : jobNames) {
            OfflineJob job = jobs.get(name);
            log.info("===== 开始作业: {} =====", name);
            long t0 = System.currentTimeMillis();
            try {
                job.run(args);
            } catch (Exception failure) {
                var safe = JobFailures.publicFailure(failure);
                log.error("{} job={}", safe.errorCode().displayMessage(), name);
                throw safe;
            }
            log.info("===== 完成作业: {},耗时 {} ms =====", name, System.currentTimeMillis() - t0);
        }
    }
}
