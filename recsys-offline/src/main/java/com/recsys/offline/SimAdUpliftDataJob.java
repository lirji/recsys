package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    public SimAdUpliftDataJob(@Qualifier("adDbJdbc") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String name() { return "sim-ad-uplift-data"; }

    @Override
    public void run(ApplicationArguments args) {
        int users = intArg(args,"users",5000); long seed = intArg(args,"seed",42);
        String run = arg(args,"run-id","qa-" + System.currentTimeMillis());
        Random random = new Random(seed); Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        List<Object[]> assignments = new ArrayList<>(users); List<Object[]> facts = new ArrayList<>();
        int treated=0,control=0,positive=0;
        for(int i=0;i<users;i++){
            long user=1_000_000L+i; boolean treatment=random.nextBoolean(); boolean high=i%2==0;
            double baseline=high?0.08:0.12; double effect=high?0.14:-0.06;
            double probability=Math.max(0.001,Math.min(0.999,baseline+(treatment?effect:0.0)));
            boolean outcome=random.nextDouble()<probability; Instant assigned=base.plusSeconds(i);
            long ad=high?900001L:900002L; long advertiser=high?910001L:910002L;
            assignments.add(new Object[]{run+":"+i,run+":"+i,user,ad,advertiser,920000L+(i%20),
                    treatment,0.5,0.1,0.2,high?1.2:0.8,0.7,2.0,"uplift-qa","collector",
                    Timestamp.from(assigned),Timestamp.from(assigned.plus(3,ChronoUnit.DAYS))});
            if(outcome){facts.add(new Object[]{run+":outcome:"+i,advertiser,user,"purchase",100.0,
                    Timestamp.from(assigned.plus(1,ChronoUnit.DAYS))});positive++;}
            if(treatment)treated++;else control++;
        }
        jdbc.batchUpdate("INSERT INTO ad_uplift_assignment(assignment_id,request_id,user_id,ad_id,advertiser_id,"+
                "item_id,treatment,propensity,pctr,pcvr,quality,relevance,bid,ad_bucket,model_version,assigned_at,"+
                "outcome_due_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",assignments);
        jdbc.batchUpdate("INSERT INTO ad_conversion_fact(event_id,advertiser_id,user_id,objective,conversion_value,"+
                "occurred_at) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",facts);
        log.info("模拟 uplift 数据 run={} users={} treatment={} control={} outcome={}",run,users,treated,control,positive);
    }
    private static String arg(ApplicationArguments a,String k,String d){List<String>v=a.getOptionValues(k);return v==null||v.isEmpty()?d:v.get(0);}
    private static int intArg(ApplicationArguments a,String k,int d){return Integer.parseInt(arg(a,k,String.valueOf(d)));}
}
