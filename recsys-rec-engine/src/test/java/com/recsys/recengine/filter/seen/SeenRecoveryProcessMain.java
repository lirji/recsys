package com.recsys.recengine.filter.seen;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

/** 只在测试独占数据库/随机topic运行的独立JVM；halt断点位于真实产品副作用之后。 */
public final class SeenRecoveryProcessMain {
    private SeenRecoveryProcessMain() { }
    public static void main(String[] args) throws Exception {
        var e=System.getenv();String jdbc=e.get("SEEN_TEST_JDBC");
        if(jdbc==null || !jdbc.contains("currentSchema=seen_"))throw new IllegalArgumentException("仅限独占测试schema");
        var f=new SeenRecoveryFixture(new DriverManagerDataSource(jdbc,e.get("SEEN_TEST_USER"),e.get("SEEN_TEST_PASSWORD")));
        String phase=args[0];
        SeenProjectionService.Probe probe=(p,r)->{if(p.name().equals(phase))java.lang.Runtime.getRuntime().halt(73);};
        try(var r=f.new Runtime(e.get("SEEN_TEST_BOOTSTRAP"),e.get("SEEN_TEST_TOPIC"),e.get("SEEN_TEST_GROUP"),e.get("SEEN_TEST_REDIS_HOST"),Integer.parseInt(e.get("SEEN_TEST_REDIS_PORT")),
                phase.equals("RECOVERY")||phase.equals("INIT")?null:probe,phase)) {
            if(phase.equals("RECOVERY") || phase.equals("INIT")) {r.recovery.advance(UUID.fromString(args[1]));throw new IllegalStateException("预期断点未执行");}
            r.listener.start();
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(55).toNanos();
            while(System.nanoTime()<deadline)Thread.sleep(100);
            throw new IllegalStateException("未到达真实产品故障窗口");
        }
    }
}
