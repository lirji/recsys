package com.recsys.console.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemOpsServiceTest {

    @Test
    void missingRedis_returnsUnavailableEmptySnapshot() {
        SystemOps snap = new SystemOpsService(null).snapshot();
        assertThat(snap.redisAvailable()).isFalse();
        assertThat(snap.tuning()).isEmpty();
        assertThat(snap.jobs()).isEmpty();
    }

    @Test
    void parseJob_splitsDagStatusPayload() {
        SystemOps.JobStatus row = SystemOpsService.parseJob("eval", "ok@2026-09-04 08:00:00:done");
        assertThat(row.name()).isEqualTo("eval");
        assertThat(row.status()).isEqualTo("ok");
        assertThat(row.updatedAt()).isEqualTo("2026-09-04 08:00:00");
        assertThat(row.detail()).isEqualTo("done");
    }

    @Test
    void parseJob_handlesMissingDetailAndBlank() {
        assertThat(SystemOpsService.parseJob("hot", "failed@2026-09-04 08:00:00").detail()).isNull();
        assertThat(SystemOpsService.parseJob("idf", null).status()).isEqualTo("UNKNOWN");
        assertThat(SystemOpsService.parseJob("swing", "running").status()).isEqualTo("running");
    }
}
