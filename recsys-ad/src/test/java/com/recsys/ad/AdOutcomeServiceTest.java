package com.recsys.ad;

import org.junit.jupiter.api.Test;
import com.recsys.ad.persistence.mapper.addb.AdConversionFactMapper;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyDouble;

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
        AdConversionFactMapper mapper = mock(AdConversionFactMapper.class);
        when(mapper.insert(anyString(), anyLong(), anyLong(), anyString(), anyDouble(), any(java.sql.Timestamp.class)))
                .thenReturn(1, 0);
        AdOutcomeService service = new AdOutcomeService(mapper);
        Instant occurredAt = Instant.parse("2026-09-01T00:00:00Z");

        assertTrue(service.record("evt-1", 10L, 20L, "purchase", 99.0, occurredAt));
        assertFalse(service.record("evt-1", 10L, 20L, "purchase", 99.0, occurredAt));
    }

    @Test
    void invalidOutcomeDoesNotReachDatabase() {
        AdConversionFactMapper mapper = mock(AdConversionFactMapper.class);
        AdOutcomeService service = new AdOutcomeService(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> service.record("", 10L, 20L, "purchase", 1.0, Instant.now()));
        verifyNoInteractions(mapper);
    }
}
