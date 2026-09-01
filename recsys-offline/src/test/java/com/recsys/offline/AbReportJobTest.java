package com.recsys.offline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbReportJobTest {

    @Test
    void evaluateCuped_emptyIttBuckets_reportReasonForEveryRequestedBucket() {
        AbReportJob.CupedReport report = new AbReportJob.CupedReport(
                true, new AbStats.CupedModel(0, 0, 0, 0, 0, false), Map.of(), 1);

        Map<String, AbReportJob.CupedResult> results = AbReportJob.evaluateCuped(
                report, List.of("base", "treatment"), "base", 2, 0.05);

        assertEquals(2, results.size());
        assertEquals("no_eligible_users", results.get("base").status());
        assertEquals("no_eligible_users", results.get("treatment").status());
        assertEquals(1, results.get("base").crossoverUsers());
        assertEquals(12, AbReportJob.cupedCsv(results.get("base")).split(",", -1).length);
    }
}
