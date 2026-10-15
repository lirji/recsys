package com.recsys.platform.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实MVC解析/Advice/JSON序列化验证错误契约，内部详情不得跨HTTP边界。 */
class GlobalExceptionHandlerTest {
    private static final String INTERNAL_DETAIL = "SQL password=private at com.internal.Service";
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearTrace() { MDC.clear(); }

    @ParameterizedTest
    @CsvSource({"400,BAD_REQUEST", "401,UNAUTHORIZED", "403,FORBIDDEN", "404,NOT_FOUND",
            "409,CONFLICT", "422,UNPROCESSABLE_ENTITY", "429,TOO_MANY_REQUESTS",
            "500,INTERNAL_SERVER_ERROR", "502,BAD_GATEWAY", "503,SERVICE_UNAVAILABLE", "504,GATEWAY_TIMEOUT"})
    void businessHttpStatusAndExistingCodeArePreservedWithoutDetail(int status, String code) throws Exception {
        var response = mvc.perform(get("/status/" + status)).andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("SQL", "password", "com.internal");
    }

    @Test
    void unknownOrSuccessfulExceptionStatusCannotMasqueradeAsSuccess() throws Exception {
        for (int status : new int[]{599, 200}) {
            mvc.perform(get("/status/" + status)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
        }
    }

    @Test
    void unexpectedFailureKeepsLegacyCodeAndWireMetadata() throws Exception {
        MDC.put("traceId", "abc-123");
        var response = mvc.perform(get("/unexpected")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.traceId").value("abc-123"))
                .andExpect(jsonPath("$.path").value("/unexpected"))
                .andExpect(jsonPath("$.timestamp").isNumber()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(INTERNAL_DETAIL);
    }

    @Test
    void illegalArgumentDoesNotLeakInternalMessage() throws Exception {
        mvc.perform(get("/argument")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求参数不合法，请检查后重试"));
    }

    @Test
    void accessDeniedRetainsSafeForbiddenContract() throws Exception {
        mvc.perform(get("/denied")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("无权访问该资源"));
    }

    @Test
    void malformedJsonIsClientFailureWithoutParserDetails() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{broken-private"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求参数不合法，请检查后重试"));
    }

    @Test
    void fieldValidationStillHasStructuredFields() throws Exception {
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.name").value("名称不能为空"));
    }

    @Test
    void resourceFailureImplementingErrorResponseKeeps404() throws Exception {
        mvc.perform(get("/missing-resource")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void frameworkMethodMediaAndMissingParameterFailuresAreNot500() throws Exception {
        mvc.perform(get("/body")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("private"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/parameter")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void retryAfterHeaderSurvivesSafeBodyMapping() throws Exception {
        mvc.perform(get("/rate-limit")).andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @RestController
    static class Probe {
        @GetMapping("/status/{status}")
        String status(@PathVariable int status) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(status), INTERNAL_DETAIL);
        }
        @GetMapping("/unexpected")
        String unexpected() { throw new IllegalStateException(INTERNAL_DETAIL); }
        @GetMapping("/argument")
        String argument() { throw new IllegalArgumentException(INTERNAL_DETAIL); }
        @GetMapping("/denied")
        String denied() { throw new AccessDeniedException(INTERNAL_DETAIL); }
        @GetMapping("/missing-resource")
        String resource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, INTERNAL_DETAIL);
        }
        @GetMapping("/parameter")
        String parameter(@RequestParam String required) { return required; }
        @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@Valid @RequestBody Input input) { return input.name(); }
        @GetMapping("/rate-limit")
        String rateLimit() {
            throw new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS) {
                @Override public HttpHeaders getHeaders() {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set(HttpHeaders.RETRY_AFTER, "30");
                    return headers;
                }
            };
        }
    }

    record Input(@NotBlank(message = "名称不能为空") String name) {}
}
