package com.recsys.offline;

import org.springframework.boot.DefaultApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.AdReportMapper;
import com.recsys.offline.persistence.mapper.primary.AbReportMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;

/** 固定报表目录的作业在独立临时工作目录执行，避免覆盖仓库已有报表。 */
public final class ExperimentReportFixture {
    /** 只连接父测试独占的PG；即使SQL或文件失败也检查真实连接释放。 */
    public static void main(String[] args) throws Exception {
        var source = new TrackedDriverDataSource(args[0], args[1], args[2]);
        var sessions = new SqlSessionTemplate(SpringSqlSessions.create(source, "classpath*:mapper/offline/primary/**/*.xml"));
        OfflineJob job = switch (args[3]) {
            case "ad" -> new AdReportJob(sessions.getMapper(AdReportMapper.class));
            case "ab" -> new AbReportJob(sessions.getMapper(AbReportMapper.class));
            default -> throw new IllegalArgumentException("未知测试作业");
        };
        try {
            job.run(new DefaultApplicationArguments(Arrays.copyOfRange(args, 4, args.length)));
        } catch (com.recsys.common.error.PublicErrorException failure) {
            System.out.println(failure.errorCode().displayMessage());
            throw failure;
        } finally {
            source.assertClosed(new JdbcTemplate(new DriverManagerDataSource(
                    args[0].replace("ApplicationName=experiment-reader", "ApplicationName=experiment-observer"),
                    args[1], args[2])), "experiment-reader");
        }
    }
}
