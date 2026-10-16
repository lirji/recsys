package com.recsys.advertiser.catalog;

import com.recsys.advertiser.AdvertiserApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.nio.file.*;

/** 独立JVM只启动真实产品装配并保存安全端口/bean计数；没有替代业务Sink或故障注入Bean。 */
public final class CatalogApplicationProcess {
    public static void main(String[] args) throws Exception {
        var context=(ServletWebServerApplicationContext)SpringApplication.run(AdvertiserApplication.class,args);
        String reserve=System.getenv("CATALOG_FIXTURE_RESERVE_ROOT");
        if(reserve!=null) {
            var source=java.util.UUID.fromString(System.getenv("AD_CATALOG_SOURCE_ID"));
            var key=CatalogProtocol.CommandKey.root(source,java.util.UUID.fromString(reserve));
            var mutation=new CatalogMutation(CatalogProtocol.Kind.ROOT_CREATE,0,null,null,null,
                    new com.recsys.advertiser.dto.AdvertiserUpsert("reserved-exit",90.0,null),null,null,null);
            context.getBean(CatalogCommandRepository.class).admitRoot(new CatalogProtocol.Submission(key,CatalogProtocol.Kind.ROOT_CREATE,
                    context.getBean(CatalogJson.class).encode(new CatalogFragments.Request(mutation,null)),"owner","开户预留后独立进程退出"));
        }
        String lastClaim=System.getenv("CATALOG_FIXTURE_LAST_CLAIM");
        if(lastClaim!=null) {
            var source=java.util.UUID.fromString(System.getenv("AD_CATALOG_SOURCE_ID"));
            var id=java.util.UUID.fromString(lastClaim);var key=CatalogProtocol.CommandKey.advertiser(source,1,id);
            var commands=context.getBean(CatalogCommandRepository.class);var steps=context.getBean(CatalogStepRepository.class);
            var outbox=context.getBean(CatalogOutboxRepository.class);var step=exitStep(commands.find(key));
            if(awaitClaim(()->commands.claim(key)).attempts()!=3 || awaitClaim(()->steps.claim(step)).attempts()!=3)
                throw new IllegalStateException("未进入第三次实际领取");
            var event=awaitClaim(()->{var rows=outbox.claim(1,source);return rows.isEmpty()?null:rows.getFirst();});
            if(event.attempts()!=5 || !event.eventId().equals(System.getenv("CATALOG_FIXTURE_LAST_EVENT")))
                throw new IllegalStateException("未进入第五次实际发送领取");
        }
        String initialization=System.getenv("CATALOG_FIXTURE_INITIALIZE");
        if(initialization!=null) {
            // 仅验收宿主驱动真实内部owner服务，无替代Bean；父进程负责在明确检查点强退。
            var service=context.getBean(CatalogInitializationService.class);var id=java.util.UUID.fromString(initialization);
            var source=java.util.UUID.fromString(System.getenv("AD_CATALOG_SOURCE_ID"));
            service.initialize(id,new CatalogInitializationService.Input(source,CatalogProtocol.TOPOLOGY,com.recsys.common.ad.VersionedAdCatalogEvent.TOPIC,
                    java.util.List.of(0,1),java.util.Map.of("init-test",7,"http-a",8,"http-b",9)),"maintenance-owner","独立JVM初始化恢复");
            int budget="quiesce-first".equals(System.getenv("CATALOG_FIXTURE_INITIALIZE_MODE"))?2:100;
            boolean done=false;
            for(int step=0;step<budget;step++)if(service.advance(id).completed()){done=true;break;}
            if(budget==100 && !done)throw new IllegalStateException("独占验收初始化未完成");
        }
        var values=java.util.Map.of("port",context.getWebServer().getPort(),
                "routers",context.getBeansOfType(CatalogWriteRouter.class).size(),"workers",context.getBeansOfType(CatalogWorker.class).size(),
                "publishers",context.getBeansOfType(CatalogKafkaPublisher.class).size(),"initializers",context.getBeansOfType(CatalogInitializationService.class).size());
        Files.writeString(Path.of(System.getenv("CATALOG_FIXTURE_READY")),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values));
    }

    /** 仅构造验收中的原始本地步骤输入；无业务替代实现或SQL写入。 */
    static CatalogProtocol.StepRequest exitStep(CatalogProtocol.CommandRow command) {
        return new CatalogProtocol.StepRequest(1,command.key(),"PREPARE",1,102L,null,command.requestBytes(),"[]");
    }
    private static <T>T awaitClaim(java.util.function.Supplier<T> action) throws InterruptedException {
        for(int i=0;i<150;i++){T result=action.get();if(result!=null)return result;Thread.sleep(100);}
        throw new IllegalStateException("实际持久退避内未能领取");
    }
}
