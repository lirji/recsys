package com.recsys.ad;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdOutcomeServiceTest {

    @Test
    void duplicateEventIdIsReportedAsIdempotentRetry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1, 0);
        AdOutcomeService service = new AdOutcomeService(jdbc);
        Instant occurredAt = Instant.parse("2026-09-01T00:00:00Z");

        assertTrue(service.record("evt-1", 10L, 20L, "purchase", 99.0, occurredAt));
        assertFalse(service.record("evt-1", 10L, 20L, "purchase", 99.0, occurredAt));
    }

    @Test
    void invalidOutcomeDoesNotReachDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdOutcomeService service = new AdOutcomeService(jdbc);

        assertThrows(IllegalArgumentException.class,
                () -> service.record("", 10L, 20L, "purchase", 1.0, Instant.now()));
        verifyNoInteractions(jdbc);
    }
}
