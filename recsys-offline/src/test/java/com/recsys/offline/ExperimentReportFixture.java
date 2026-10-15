package com.recsys.offline;

import org.springframework.boot.DefaultApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.AdReportMapper;
import com.recsys.offline.persistence.mapper.primary.AbReportMapper;
import com.recsys.offline.persistence.model.AdEventSource;
import org.apache.ibatis.session.ResultHandler;
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
        var options = new DefaultApplicationArguments(Arrays.copyOfRange(args, 4, args.length));
        OfflineJob job = switch (args[3]) {
            case "ad" -> new AdReportJob(countBoundary(sessions.getMapper(AdReportMapper.class), options));
            case "ab" -> new AbReportJob(sessions.getMapper(AbReportMapper.class));
            default -> throw new IllegalArgumentException("未知测试作业");
        };
        try {
            job.run(options);
        } catch (com.recsys.common.error.PublicErrorException failure) {
            System.out.println(failure.errorCode().displayMessage());
            throw failure;
        } finally {
            source.assertClosed(new JdbcTemplate(new DriverManagerDataSource(
                    args[0].replace("ApplicationName=experiment-reader", "ApplicationName=experiment-observer"),
                    args[1], args[2])), "experiment-reader");
        }
    }

    /** 仅替换计数返回边界；曝光/分桶仍用真实PG，不宣称模拟数据库网络故障。 */
    private static AdReportMapper countBoundary(AdReportMapper delegate, DefaultApplicationArguments options) {
        return new AdReportMapper() {
            @Override public void scanPositions(AdEventSource source, String since, ResultHandler<Position> handler) {
                delegate.scanPositions(source, since, handler);
            }
            @Override public void scanBuckets(AdEventSource source, String since, ResultHandler<Bucket> handler) {
                delegate.scanBuckets(source, since, handler);
            }
            @Override public Long countInvalidClicks(AdEventSource source, String since) {
                if (options.containsOption("fixture-count-failure")) throw new IllegalStateException("private fixture count failure");
                if (options.containsOption("fixture-count-null")) return null;
                if (options.containsOption("fixture-count-negative")) return -1L;
                return delegate.countInvalidClicks(source, since);
            }
        };
    }
}
