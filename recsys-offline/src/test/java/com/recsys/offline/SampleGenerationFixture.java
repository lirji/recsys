package com.recsys.offline;

import com.recsys.common.feature.FeatureService;
import com.recsys.offline.persistence.mapper.primary.TrainingSampleMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;
import java.util.Map;

/** 子进程在测试临时目录运行原固定CSV路径，避免触碰开发者训练数据。 */
public final class SampleGenerationFixture {
    /** 只连接父测试独占PG；进程退出前检查真实连接已关闭。 */
    public static void main(String[] args) throws Exception {
        var source = new TrackedDriverDataSource(args[0],args[1],args[2]);
        var sessions = new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml"));
        var inputs = sessions.getMapper(TrainingSampleMapper.class);
        FeatureService features = new FeatureService() {
            public Map<String,Double> userFeatures(long id) {
                return Map.of("avg_rating",3.5,"log_cnt_norm",0.8,"catavg:A",4.0,"catcnt_norm:A",0.6,"catratio:A",0.5);
            }
            public Map<String,Double> itemFeatures(long id) {
                return Map.of("avg_rating",4.0,"log_cnt_norm",0.7,"std_rating",0.3);
            }
        };
        OfflineJob job = switch(args[3]) {
            case "ctr" -> new GenSamplesJob(inputs,features);
            case "mt" -> new GenSamplesMtJob(inputs);
            case "impr" -> new GenSamplesImprJob(inputs);
            default -> throw new IllegalArgumentException("未知测试作业");
        };
        try {
            job.run(new DefaultApplicationArguments(Arrays.copyOfRange(args,4,args.length)));
        } catch (com.recsys.common.error.PublicErrorException failure) {
            System.err.println(failure.errorCode().displayMessage());
            throw failure;
        } finally {
            source.assertClosed(new JdbcTemplate(new DriverManagerDataSource(args[0].replace("ApplicationName=samples-reader","ApplicationName=samples-observer"),args[1],args[2])),"samples-reader");
        }
    }
}
