package com.recsys.platform.security;

import com.recsys.platform.web.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下游 servlet 安全链切片验证(P0-5):证明 {@link InternalSecurityConfig} + {@link InternalTokenAuthFilter}
 * + 方法级 {@code @PreAuthorize} 三者在运行时协同——放行公开路径、拦匿名(401)、拦角色不足(403)、放行合法角色(200)。
 * 无需 Docker/DB,进 CI 永久守护。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "recsys.security.enabled=true",
        "recsys.security.internal-secret=slice-test-secret-0123456789abcd",
        "recsys.security.permit-paths=/actuator/health/**,/api/open/**"
})
@AutoConfigureMockMvc
class ServletSecuritySmokeTest {

    private static final String SECRET = "slice-test-secret-0123456789abcd";

    @Autowired
    private MockMvc mvc;

    private String header(String subject, String roles) {
        return InternalToken.mint(subject, roles, "test", 300, Instant.now().getEpochSecond(), SECRET);
    }

    @Test
    void publicPath_open_withoutToken() throws Exception {
        mvc.perform(get("/api/open/ping")).andExpect(status().isOk());
    }

    @Test
    void protectedPath_anonymous_401() throws Exception {
        mvc.perform(get("/api/secure/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPath_adminToken_200() throws Exception {
        mvc.perform(get("/api/secure/ping").header("X-Internal-Auth", header("admin", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void protectedPath_wrongRole_403() throws Exception {
        mvc.perform(get("/api/secure/ping").header("X-Internal-Auth", header("bob", "USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedPath_tamperedToken_401() throws Exception {
        mvc.perform(get("/api/secure/ping").header("X-Internal-Auth", "garbage.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBody_400_withApiError() throws Exception {
        // 空 name 违反 @NotBlank → GlobalExceptionHandler 统一返回 400 + ApiError(code=VALIDATION_FAILED)
        mvc.perform(post("/api/open/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.name").exists());
    }

    @Test
    void invalidQueryParameterType_400_withApiError() throws Exception {
        mvc.perform(get("/api/open/user").queryParam("userId", "u1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.userId").exists());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({InternalSecurityConfig.class, MethodSecurityConfig.class,
            GlobalExceptionHandler.class, TestApp.ProbeController.class})
    static class TestApp {

        @RestController
        static class ProbeController {

            @GetMapping("/api/open/ping")
            String open() {
                return "ok";
            }

            @GetMapping("/api/open/user")
            String user(long userId) {
                return Long.toString(userId);
            }

            @GetMapping("/api/secure/ping")
            @PreAuthorize("hasRole('ADMIN')")
            String secure() {
                return "ok";
            }

            @PostMapping("/api/open/echo")
            String echo(@Valid @RequestBody EchoRequest req) {
                return req.name();
            }
        }

        record EchoRequest(@NotBlank String name) {
        }
    }
}
