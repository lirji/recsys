package com.recsys.behavior.events;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.UUID;

/** 独立JVM只连接父测试给定的独占库；真实进程退出使未提交的源快照/item一起回滚。 */
public final class BehaviorHistoryCrashProbe {
    private BehaviorHistoryCrashProbe() { }
    public static void main(String[] args) throws Exception {
        var env=System.getenv();var ds=new DriverManagerDataSource(env.get("S15B2_JDBC"),env.get("S15B2_USER"),env.get("S15B2_PASSWORD"));
        var fixture=new BehaviorEventsFixture(ds);
        if(args[0].equals("before-item")) fixture.sessions.getConfiguration().addInterceptor(new BeforeItem());
        var history=new BehaviorHistoryRepository(fixture.sessions,new DataSourceTransactionManager(ds),new MockEnvironment());
        history.advanceHistory(UUID.fromString(args[1]));halt("after-batch",92);
    }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    static class BeforeItem implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if(((MappedStatement)invocation.getArgs()[0]).getId().endsWith("BehaviorHistoryMapper.saveItem")) halt("before-item",91);
            return invocation.proceed();
        }
    }
    private static void halt(String window,int code) {
        System.out.println("S15B2_WINDOW="+window);System.out.flush();Runtime.getRuntime().halt(code);
    }
}
