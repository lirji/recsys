package com.recsys.offline;

import com.recsys.offline.persistence.mapper.primary.AdAttributionMapper;
import com.recsys.offline.persistence.mapper.primary.AdQualityMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

/** 分析作业在临时目录子进程运行，固定PG时间只属于测试事务。 */
public final class AdAnalysisFixture {
    /** 只使用父测试独占PG/Redis，结束前验证连接已关闭。 */
    public static void main(String[] args) throws Exception {
        var source=new TrackedDriverDataSource(args[0],args[1],args[2]);var db=new JdbcTemplate(source);
        var config=new RedisStandaloneConfiguration(args[3],Integer.parseInt(args[4]));
        if(args[6].equals("acl")){config.setUsername("quality-test");config.setPassword("test-only-password");}
        var factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();factory.start();
        var redis=new StringRedisTemplate(factory);
        var sessions=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml"));
        OfflineJob job=switch(args[5]){case "attribution"->new AttributionJob(sessions.getMapper(AdAttributionMapper.class));case "quality"->new QualityScoreJob(sessions.getMapper(AdQualityMapper.class),redis);default->throw new IllegalArgumentException("未知测试作业");};
        var options=new DefaultApplicationArguments(Arrays.copyOfRange(args,7,args.length));
        try {
            if(args[6].equals("read-only")){job.run(options);}
            else new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
                for(String table:List.of("ad_event","ad_event_log"))db.execute("UPDATE "+table+" SET ts=now()+offset_s*interval '1 second' WHERE offset_s IS NOT NULL");
                try{job.run(options);}catch(Exception error){throw new RuntimeException(error);}
            });
        } finally {
            factory.destroy();source.assertClosed(new JdbcTemplate(new DriverManagerDataSource(args[0].replace("ApplicationName=ad-analysis-reader","ApplicationName=ad-analysis-observer"),args[1],args[2])),"ad-analysis-reader");
        }
    }
}
