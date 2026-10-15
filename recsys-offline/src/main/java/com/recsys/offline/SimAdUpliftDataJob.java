package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.UpliftSimulationWriter;
import com.recsys.offline.persistence.mapper.addb.UpliftSimulationMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 教学/QA 用已知异质处理效应数据：高 quality 段正 uplift，低 quality 段负 uplift。 */
@Component
public class SimAdUpliftDataJob implements OfflineJob {
    private static final Logger log = LoggerFactory.getLogger(SimAdUpliftDataJob.class);
    private final UpliftSimulationWriter writer;

    /** 两个原批次由广告自有库持久层执行，不改变作业事务边界。 */
    public SimAdUpliftDataJob(UpliftSimulationWriter writer) { this.writer = writer; }

    /** 保持既有教学/QA命令入口。 */
    @Override public String name() { return "sim-ad-uplift-data"; }

    /** 随机生成顺序与分配后写事实的顺序均需保持，供同种子数据对照。 */
    @Override
    public void run(ApplicationArguments args) {
        int users = intArg(args,"users",5000); long seed = intArg(args,"seed",42);
        String run = arg(args,"run-id","qa-" + System.currentTimeMillis());
        if (users < 0) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        Random random = new Random(seed); Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        List<UpliftSimulationMapper.Assignment> assignments = new ArrayList<>(users);
        List<UpliftSimulationMapper.Fact> facts = new ArrayList<>();
        int treated=0,control=0,positive=0;
        for(int i=0;i<users;i++){
            long user=1_000_000L+i; boolean treatment=random.nextBoolean(); boolean high=i%2==0;
            double baseline=high?0.08:0.12; double effect=high?0.14:-0.06;
            double probability=Math.max(0.001,Math.min(0.999,baseline+(treatment?effect:0.0)));
            boolean outcome=random.nextDouble()<probability; Instant assigned=base.plusSeconds(i);
            long ad=high?900001L:900002L; long advertiser=high?910001L:910002L;
            assignments.add(new UpliftSimulationMapper.Assignment(run+":"+i,run+":"+i,user,ad,advertiser,920000L+(i%20),
                    treatment,0.5,0.1,0.2,high?1.2:0.8,0.7,2.0,"uplift-qa","collector",
                    Timestamp.from(assigned),Timestamp.from(assigned.plus(3,ChronoUnit.DAYS))));
            if(outcome){facts.add(new UpliftSimulationMapper.Fact(run+":outcome:"+i,advertiser,user,"purchase",100.0,
                    Timestamp.from(assigned.plus(1,ChronoUnit.DAYS))));positive++;}
            if(treatment)treated++;else control++;
        }
        writer.assignments(assignments);
        writer.facts(facts);
        log.info("模拟 uplift 数据 run={} users={} treatment={} control={} outcome={}",run,users,treated,control,positive);
    }
    private static String arg(ApplicationArguments a,String k,String d){return JobArguments.text(a,k,d);}
    private static int intArg(ApplicationArguments a,String k,int d){return JobArguments.integer(a,k,d);}
}
