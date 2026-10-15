package com.recsys.offline;

import org.springframework.boot.DefaultApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.DataQualityInputMapper;
import com.recsys.offline.persistence.mapper.derived.DataQualityCoverageMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

/** 临时目录中的独占双源夹具，避免固定eval路径覆盖项目文件。 */
public final class DataQualityFixture {
    /** freeze仅测试窗口端点；故障隔离场景沿作业原自动提交模式运行。 */
    public static void main(String[] args) throws Exception {
        var primary = new TrackedDriverDataSource(args[0], args[2], args[3]);
        var derived = new TrackedDriverDataSource(args[1], args[2], args[3]);
        var db = new JdbcTemplate(primary);
        var inputs = new SqlSessionTemplate(SpringSqlSessions.create(primary,"classpath*:mapper/offline/primary/**/*.xml")).getMapper(DataQualityInputMapper.class);
        var coverage = new SqlSessionTemplate(SpringSqlSessions.create(derived,"classpath*:mapper/offline/derived/**/*.xml")).getMapper(DataQualityCoverageMapper.class);
        var job = new DataQualityJob(inputs, coverage);
        var options = new DefaultApplicationArguments(Arrays.copyOfRange(args,5,args.length));
        try {
            if (args[4].equals("freeze")) {
                new TransactionTemplate(new DataSourceTransactionManager(primary)).executeWithoutResult(status -> {
                    for (String table : List.of("user_behavior","behavior_log")) db.execute("UPDATE "+table+" SET ts=now()+offset_s*interval '1 second' WHERE offset_s IS NOT NULL");
                    try { job.run(options); } catch (Exception error) { throw new RuntimeException(error); }
                });
            } else job.run(options);
        } catch (Exception failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof com.recsys.common.error.PublicErrorException visible) {
                    System.out.println(visible.errorCode().displayMessage());break;
                }
            }
            throw failure;
        } finally {
            var observer = new JdbcTemplate(new DriverManagerDataSource(args[0].replace("ApplicationName=dq-primary-reader","ApplicationName=dq-observer"),args[2],args[3]));
            primary.assertClosed(observer,"dq-primary-reader"); derived.assertClosed(observer,"dq-derived-reader");
        }
    }
}
