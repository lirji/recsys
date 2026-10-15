package com.recsys.gateway.security;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** 两种运行模式都先匹配 behavior 专属路由，不能被 engine sandbox 通配路径截获。 */
@Tag("natural-exposure")
class NaturalExposureRouteTest {
    @Test void staticAndDiscoveryRoutesSendNaturalReceiptsToBehaviorFirst() {
        for(String resource:new String[]{"application.yml","application-static.yml"}) {
            var loader=new YamlPropertiesFactoryBean();loader.setResources(new ClassPathResource(resource));var p=loader.getObject();
            int natural=-1,engine=-1;
            for(int i=0;i<20;i++) {
                String prefix="spring.cloud.gateway.routes["+i+"]";
                if("natural-impressions".equals(p.getProperty(prefix+".id"))) {
                    natural=i;assertEquals("Path=/api/sandbox/natural-impressions/**",p.getProperty(prefix+".predicates[0]"));
                    assertTrue(p.getProperty(prefix+".uri").contains(resource.contains("static")?"BEHAVIOR_PORT":"recsys-behavior"));
                }
                if("rec-engine".equals(p.getProperty(prefix+".id"))) engine=i;
            }
            assertTrue(natural>=0 && engine>natural,resource);
        }
    }
}
