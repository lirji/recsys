package com.recsys.offline;

import com.recsys.common.feature.FeatureService;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.zaxxer.hikari.HikariDataSource;
import com.recsys.offline.persistence.mapper.primary.AdCvrInputMapper;
import com.recsys.offline.persistence.mapper.sharded.AdItemMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 独占PG及真实分片的子进程夹具，固定路径CSV只写测试临时目录。 */
public final class AdCvrSamplesFixture {
    /** freeze模式在原数据源外层事务内冻结PG now，精确验证窗口边界和elapsed列。 */
    public static void main(String[] args) throws Exception {
        var primary=new TrackedDriverDataSource(args[0],args[2],args[3]);
        var db=new JdbcTemplate(primary);
        var ds0=pool(args[0].replace("&currentSchema=cvr&ApplicationName=cvr-reader",""),args[2],args[3]);
        var ds1=pool(args[1],args[2],args[3]);
        String yaml;
        try(var input=AdCvrSamplesFixture.class.getResourceAsStream("/sharding.yaml")) {
            yaml=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        }
        var sharded=YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0",ds0,"ds_1",ds1),
                yaml.substring(yaml.indexOf("\nrules:")+1).getBytes(StandardCharsets.UTF_8));
        FeatureService features=new FeatureService() {
            public Map<String,Double> userFeatures(long id) {
                if(args[4].equals("user-failure") && id==3)throw new IllegalStateException("fixture-user-failure");
                return Map.of("user_act_norm",0.3,"user_avg_rating",4.0,"catavg:A",4.5);
            }
            public Map<String,Double> itemFeatures(long id) {return Map.of("item_pop_norm",0.6,"item_avg_rating",3.0);}
        };
        ContentService content=new ContentService() {
            public Item findById(long id){return id==102?null:new Item(id,"title","A",List.of(),"",0);}
            public Map<Long,Item> findByIds(List<Long> ids){var found=new HashMap<Long,Item>();for(long id:ids){var item=findById(id);if(item!=null)found.put(id,item);}return found;}
            public List<Long> allItemIds(){throw new UnsupportedOperationException();}
            public void save(Item item){throw new UnsupportedOperationException();}
            public long count(){throw new UnsupportedOperationException();}
        };
        var inputs=new SqlSessionTemplate(SpringSqlSessions.create(primary,"classpath*:mapper/offline/primary/**/*.xml")).getMapper(AdCvrInputMapper.class);
        var ads=new SqlSessionTemplate(SpringSqlSessions.create(sharded,"classpath*:mapper/offline/sharded/**/*.xml")).getMapper(AdItemMapper.class);
        var job=new GenAdCvrSamplesJob(inputs,ads,features,content);
        var options=new DefaultApplicationArguments(Arrays.copyOfRange(args,5,args.length));
        try {
            if(args[4].equals("autocommit") || args[4].equals("read-only")) {
                if(args[4].equals("autocommit"))freeze(db);
                job.run(options);
            } else {
                new TransactionTemplate(new DataSourceTransactionManager(primary)).executeWithoutResult(status->{
                    freeze(db);
                    try{job.run(options);}catch(Exception error){throw new RuntimeException(error);}
                });
            }
        } finally {
            primary.assertClosed(new JdbcTemplate(new DriverManagerDataSource(args[0].replace("ApplicationName=cvr-reader","ApplicationName=cvr-observer"),args[2],args[3])),"cvr-reader");
            if(ds0.getHikariPoolMXBean().getActiveConnections()!=0 || ds1.getHikariPoolMXBean().getActiveConnections()!=0)
                throw new AssertionError("广告分片连接未归还");
            ((AutoCloseable)sharded).close();
        }
    }
    private static void freeze(JdbcTemplate db) {
        for(String table:List.of("ad_event","ad_event_log"))db.execute("UPDATE "+table+" SET ts=now()+offset_s*interval '1 second' WHERE offset_s IS NOT NULL");
    }
    private static HikariDataSource pool(String url,String user,String password) {
        var ds=new HikariDataSource();ds.setJdbcUrl(url);ds.setUsername(user);ds.setPassword(password);ds.setMaximumPoolSize(2);return ds;
    }
}
