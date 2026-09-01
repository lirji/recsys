package com.recsys.ad;

import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.SponsoredAd;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UpliftAssignmentServiceTest {
    @Test
    void assignmentIsDeterministicAndInsideUnitInterval() {
        double first = UpliftAssignmentService.uniform(42L, "salt");
        assertEquals(first, UpliftAssignmentService.uniform(42L, "salt"));
        assertTrue(first >= 0.0 && first < 1.0);
    }

    @Test
    void hashAssignmentIsApproximatelyUniform() {
        int control = 0;
        for (long user = 1; user <= 20_000; user++) {
            if (UpliftAssignmentService.uniform(user, "salt") < 0.1) control++;
        }
        assertTrue(control > 1800 && control < 2200, "10% hash bucket 应在统计容许范围内:" + control);
    }

    @Test
    void disabledCollectionDoesNotTouchDatabaseAtStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UpliftAssignmentService service = new UpliftAssignmentService(jdbc, new AdProperties());

        service.ensureTables();

        verifyNoInteractions(jdbc);
    }

    @Test
    void persistedControlAssignmentReallyRemovesLastSlot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AdProperties props = new AdProperties();
        props.getUplift().setCollectionEnabled(true);
        props.getUplift().setControlRate(0.5);
        UpliftAssignmentService service = new UpliftAssignmentService(jdbc, props);
        long controlUser = 1L;
        while (UpliftAssignmentService.uniform(controlUser, props.getUplift().getSalt()) < 0.5) {
            controlUser++;
        }
        AdCandidate candidate = new AdCandidate(101L, 11L, 1001L, 88L, 1.0,
                AdChannel.HOT_AD, 2.0, 0.9);
        SponsoredAd ad = new SponsoredAd(101L, 11L, 1001L, 88L, "ad", AdChannel.HOT_AD,
                2.0, 0.9, 0.7, 0.2, 0.2, 0.1, 1.0, 1, 0L, "CPA");

        UpliftAssignmentService.Result result = service.collect("req-1", controlUser, List.of(ad),
                List.of(candidate), Map.of(11L, 0.04),
                Map.of(101L, new UpliftEstimate(0.1, 0.2, 0.1, "a7-test")), "base");

        assertTrue(result.assigned());
        assertFalse(result.treatment());
        assertTrue(result.ads().isEmpty(), "control 必须真的留空，不能形成曝光/扣费输入");
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
