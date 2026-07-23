package com.recsys.console.system;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SystemOverviewServiceTest {

    @Test
    void overviewDoesNotEmbedHealthProbeResults() {
        SystemOverview overview = new SystemOverviewService().overview();

        assertThat(overview.projectName()).isEqualTo("recsys");
        assertThat(overview.modules()).isNotEmpty();
        assertThat(overview.apis()).isNotEmpty();
        assertThat(overview.commands()).isNotEmpty();
    }

    @Test
    void modulesIncludeAllKnownProjectParts() {
        List<String> names = new SystemOverviewService().modules().stream()
                .map(SystemOverview.ModuleInfo::name)
                .toList();

        assertThat(names).contains(
                "console",
                "recsys-gateway",
                "recsys-rec-engine",
                "recsys-behavior",
                "recsys-advertiser",
                "recsys-console",
                "recsys-offline",
                "recsys-streaming"
        );
    }

    @Test
    void healthReturnsEmptyListWhenNoTargetsConfigured() {
        SystemHealthProperties properties = new SystemHealthProperties();
        SystemHealthService service = new SystemHealthService(properties, RestClient.create());

        assertThat(service.health()).isEmpty();
    }

    @Test
    void healthKeepsPassiveJobsOutOfHttpProbe() {
        SystemHealthProperties properties = new SystemHealthProperties();
        SystemHealthProperties.Target target = new SystemHealthProperties.Target();
        target.setService("recsys-offline");
        target.setName("离线作业");
        target.setKind("job");
        target.setPassiveStatus("JOB_ONLY");
        target.setPassiveMessage("按需运行");
        properties.setTargets(List.of(target));
        SystemHealthService service = new SystemHealthService(properties, RestClient.create());

        List<SystemOverview.ServiceHealth> health = service.health();

        assertThat(health).hasSize(1);
        assertThat(health.get(0).status()).isEqualTo("JOB_ONLY");
        assertThat(health.get(0).url()).isNull();
        assertThat(health.get(0).message()).isEqualTo("按需运行");
    }

    @Test
    void healthMarksMissingTargetUrlAsUnknown() {
        SystemHealthProperties properties = new SystemHealthProperties();
        SystemHealthProperties.Target target = new SystemHealthProperties.Target();
        target.setService("missing-url");
        target.setName("missing-url");
        target.setKind("app");
        properties.setTargets(List.of(target));
        SystemHealthService service = new SystemHealthService(properties, RestClient.create());

        List<SystemOverview.ServiceHealth> health = service.health();

        assertThat(health).hasSize(1);
        assertThat(health.get(0).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void healthMarksUnreachableTargetAsDown() {
        SystemHealthProperties properties = new SystemHealthProperties();
        properties.setConnectTimeoutMs(100);
        properties.setReadTimeoutMs(100);
        SystemHealthProperties.Target target = new SystemHealthProperties.Target();
        target.setService("unreachable");
        target.setName("unreachable");
        target.setKind("app");
        target.setUrl("http://127.0.0.1:1");
        properties.setTargets(List.of(target));
        SystemHealthService service = new SystemHealthService(properties);

        List<SystemOverview.ServiceHealth> health = service.health();

        assertThat(health).hasSize(1);
        assertThat(health.get(0).status()).isEqualTo("DOWN");
        assertThat(health.get(0).message()).isEqualTo("health endpoint unreachable");
    }

    @Test
    void healthDistinguishesDownEndpointFromUnreachableEndpoint() {
        SystemHealthProperties properties = new SystemHealthProperties();
        SystemHealthProperties.Target target = new SystemHealthProperties.Target();
        target.setService("down-service");
        target.setName("down-service");
        target.setKind("app");
        target.setUrl("http://down-service:8080");
        properties.setTargets(List.of(target));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://down-service:8080/actuator/health"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"DOWN\"}"));
        SystemHealthService service = new SystemHealthService(properties, builder.build());

        List<SystemOverview.ServiceHealth> health = service.health();

        assertThat(health).hasSize(1);
        assertThat(health.get(0).status()).isEqualTo("DOWN");
        assertThat(health.get(0).message())
                .isEqualTo("health endpoint reachable but returned HTTP 503");
        server.verify();
    }
}
