package com.recsys.offline;

import com.recsys.common.rank.RankedItem;
import com.recsys.common.error.PublicErrorException;
import com.recsys.offline.persistence.mapper.primary.EvaluationInputMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.common.recall.*;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import com.recsys.rank.RankRouter;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.stream.LongStream;

/** 独占PG/Redis与临时目录，召回和排序仅固定候选，用于比较SQL迁移前后评估输出。 */
public final class EvaluationFixture {
    /** 子进程关闭检查在退出前完成，避免由进程退出掩盖读取连接泄漏。 */
    public static void main(String[] args) throws Exception {
        var source=new TrackedDriverDataSource(args[0],args[1],args[2]);
        var sessions=new SqlSessionTemplate(SpringSqlSessions.create(source,"classpath*:mapper/offline/primary/**/*.xml"));
        var inputs=sessions.getMapper(EvaluationInputMapper.class);
        var options=new DefaultApplicationArguments(Arrays.copyOfRange(args,6,args.length));
        boolean fail=options.containsOption("fixture-failures");
        Thread caller = Thread.currentThread();
        RecallService recall=ctx->{
            if (options.containsOption("fixture-interrupt")) {
                caller.interrupt();
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    System.out.println("WORKER_STOPPED");
                    throw new IllegalStateException("fixture worker interrupted", stopped);
                }
            }
            if (options.containsOption("fixture-fatal")) throw new AssertionError("fixture fatal error");
            if (options.containsOption("fixture-all-fail")) throw new IllegalStateException("fixture recall failure");
            if (options.containsOption("fixture-empty")) return List.of();
            if (options.containsOption("fixture-one-channel") && !ctx.enabledChannels().contains(RecallChannel.HOT)) return List.of();
            if(fail&&ctx.userId()==2)throw new IllegalStateException("fixture recall failure");
            var out=new ArrayList<>(LongStream.rangeClosed(1,8).mapToObj(id->new RecallItem(id,1.0/id,RecallChannel.HOT)).toList());
            out.add(new RecallItem(2,0.5,RecallChannel.HOT));return out;
        };
        var rank=new RankRouter(null,null,null,null,null,null,null,null,null,null,null){
            @Override public List<RankedItem> rank(long user,List<Long> ids,String scene,String strategy){
                if(fail&&user==3)throw new IllegalStateException("fixture rank failure");
                return ids.stream().map(id -> {
                    double score = options.containsOption("fixture-nan-score") ? Double.NaN : 1.0 / id;
                    double pctr = options.containsOption("fixture-nan-head") ? Double.NaN : 1.0 / id;
                    double pcvr = options.containsOption("fixture-overflow-head") ? Double.MAX_VALUE : id % 3 * 0.4;
                    Map<String, Double> heads = options.containsOption("fixture-no-heads")
                            || (options.containsOption("fixture-partial-heads") && id % 2 == 0)
                            ? Map.of() : Map.of("pCTR", pctr, "pCVR", pcvr);
                    return new RankedItem(id, score, heads);
                }).toList();
            }
        };
        ContentService content=new ContentService(){
            public Item findById(long id){return new Item(id,"fixture",id%2==0?"A":"B",List.of(),"",id);}
            public Map<Long,Item> findByIds(List<Long> ids){throw new UnsupportedOperationException();}
            public List<Long> allItemIds(){throw new UnsupportedOperationException();}
            public void save(Item item){throw new UnsupportedOperationException();}
            public long count(){throw new UnsupportedOperationException();}
        };
        var factory=new LettuceConnectionFactory(args[3],Integer.parseInt(args[4]));factory.afterPropertiesSet();factory.start();
        var redis=new StringRedisTemplate(factory);
        OfflineJob job=switch(args[5]){
            case "eval"->new EvalJob(inputs,recall,rank,content);
            case "tune"->new TuneFusionJob(inputs,recall,rank);
            case "calibrate"->new RecCalibrateJob(inputs,recall,rank,redis);
            default->throw new IllegalArgumentException("未知测试作业");
        };
        try{job.run(options);}
        catch (PublicErrorException failure) {
            System.out.println(failure.errorCode().displayMessage());
            System.out.println("CALLER_INTERRUPTED=" + Thread.currentThread().isInterrupted());
            throw failure;
        }
        finally{
            // 记录真实作业标记后清除，避免中断破坏独立的测试资源清理与连接核验。
            Thread.interrupted();
            factory.destroy();
            source.assertClosed(new JdbcTemplate(new DriverManagerDataSource(args[0].replace("ApplicationName=evaluation-reader","ApplicationName=evaluation-observer"),args[1],args[2])),"evaluation-reader");
        }
    }
}
