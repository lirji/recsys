package com.recsys.recengine.experiment;

import com.recsys.common.constant.RedisKeys;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实Redis/HTTP及方法授权验证旧200拒绝契约、安全码和写后未知结果。 */
@Testcontainers
class ExperimentAdminErrorTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity static class Security { }

    @BeforeAll static void prepare() {
        factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);
    }
    @AfterAll static void close(){factory.destroy();}
    @BeforeEach void reset(){redis.delete(RedisKeys.EXP_OVERRIDE);asRole("ADMIN");}
    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();}

    @Test void missingStoreKeeps200AndKnownRejectionForAllWriteRoutes() throws Exception {
        try(var context=context(null,null)) {
            var mvc=mvc(context);
            for(var request:requests()) rejected(mvc,request,ErrorCode.EXPERIMENT_STORE_UNAVAILABLE);
        }
    }
    @Test void actualAclFailuresDoNotExposeRedisMessagesAndKeepExistingValues() throws Exception {
        redis.opsForHash().put(RedisKeys.EXP_OVERRIDE,"enabled","true");
        assertEquals(0,REDIS.execInContainer("redis-cli","ACL","SETUSER","experiment-test","on",">test-password","resetkeys","~*","+@all","-hset","-del").getExitCode());
        var config=new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));
        config.setUsername("experiment-test");config.setPassword("test-password");
        var denied=new LettuceConnectionFactory(config);denied.afterPropertiesSet();denied.start();
        try(var context=context(new StringRedisTemplate(denied),null)) {
            for(var request:requests()) rejected(mvc(context),request,ErrorCode.EXPERIMENT_WRITE_UNCONFIRMED);
            assertEquals("true",redis.opsForHash().get(RedisKeys.EXP_OVERRIDE,"enabled"));
        } finally {denied.destroy();}
        try(var context=context(redis,null)) {
            mvc(context).perform(post("/api/experiment/enabled").param("value","false"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
            assertEquals("false",redis.opsForHash().get(RedisKeys.EXP_OVERRIDE,"enabled"));
        }
    }
    @Test void successfulWritesKeepFieldsAndNegativeWeightClamp() throws Exception {
        try(var context=context(redis,null)) {
            var mvc=mvc(context);
            mvc.perform(post("/api/experiment/rank/enabled").param("value","false"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.field").value("rank.enabled")).andExpect(jsonPath("$.value").value("false"));
            mvc.perform(post("/api/experiment/rank/v1/weight").param("value","-1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("0"));
            assertEquals("0",redis.opsForHash().get(RedisKeys.EXP_OVERRIDE,"rank.v1.weight"));
            mvc.perform(delete("/api/experiment/override")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.cleared").value(true));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeys.EXP_OVERRIDE)));
        }
    }
    @Test void failureAfterWriteReportsUnknownRatherThanClaimingNotApplied() throws Exception {
        var override=mock(ExperimentOverrideService.class);
        doThrow(new IllegalStateException("PRIVATE_INVALIDATE_DETAIL")).when(override).invalidate();
        try(var context=context(redis,override)) {
            rejected(mvc(context),post("/api/experiment/enabled").param("value","true"),ErrorCode.EXPERIMENT_WRITE_UNCONFIRMED);
            assertEquals("true",redis.opsForHash().get(RedisKeys.EXP_OVERRIDE,"enabled"));
            rejected(mvc(context),delete("/api/experiment/override"),ErrorCode.EXPERIMENT_WRITE_UNCONFIRMED);
            assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeys.EXP_OVERRIDE)));
        }
    }
    @Test void nonAdminCannotReachAnyWriteDespiteValidSession() throws Exception {
        asRole("USER");
        try(var context=context(redis,null)) {
            for(var request:requests()) mvc(context).perform(request).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeys.EXP_OVERRIDE)));
        }
    }
    private static List<MockHttpServletRequestBuilder> requests(){return List.of(
            post("/api/experiment/enabled").param("value","false"),
            post("/api/experiment/rank/enabled").param("value","false"),
            post("/api/experiment/rank/v1/weight").param("value","10"),
            delete("/api/experiment/override"));}
    private static void rejected(MockMvc mvc,MockHttpServletRequestBuilder request,ErrorCode code)throws Exception {
        String body=mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(code.code())).andExpect(jsonPath("$.reason").value(code.message()))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("PRIVATE_"));assertFalse(body.contains("NOPERM"));assertFalse(body.contains("test-password"));
    }
    private static AnnotationConfigApplicationContext context(StringRedisTemplate target,ExperimentOverrideService override){
        var context=new AnnotationConfigApplicationContext();context.register(Security.class);
        if(target!=null)context.registerBean(StringRedisTemplate.class,()->target);
        context.registerBean(ExperimentAdminController.class,()->new ExperimentAdminController(
                context.getBeanProvider(StringRedisTemplate.class),override==null?new ExperimentOverrideService(context.getBeanProvider(StringRedisTemplate.class)):override,
                new ExperimentProperties()));
        context.refresh();return context;
    }
    private static MockMvc mvc(AnnotationConfigApplicationContext context){return MockMvcBuilders
            .standaloneSetup(context.getBean(ExperimentAdminController.class)).setControllerAdvice(new GlobalExceptionHandler()).defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();}
    private static void asRole(String role){SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("test-admin","unused",List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
}
