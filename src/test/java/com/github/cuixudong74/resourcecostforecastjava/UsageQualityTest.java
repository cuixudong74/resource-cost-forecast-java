package com.github.cuixudong74.resourcecostforecastjava;

import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality;
import com.github.cuixudong74.resourcecostforecastjava.domain.EstimateStatus;
import com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.estimation.UsageStore;
import com.github.cuixudong74.resourcecostforecastjava.rules.BillingEngine;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缺失与迟到数据：区分跳过原因、保留原始到达时间、不用零值冒充。 */
class UsageQualityTest {

    @Test
    void missingPeriodsArePlaceholdersNotZero() {
        UsageStore store = new UsageStore();
        store.ingest(new UsagePoint("t", "r", "cpu", 2L, 5.0, Instant.now(), DataQuality.ON_TIME));
        List<UsagePoint> series = store.series("t", "r", "cpu", 1L, 3L, null);

        assertEquals(DataQuality.MISSING, series.get(0).quality());
        assertNull(series.get(0).value(), "缺失值必须为 null，不得用 0 冒充");
        assertEquals(DataQuality.ON_TIME, series.get(1).quality());
        assertEquals(DataQuality.MISSING, series.get(2).quality());
    }

    @Test
    void lateArrivalIsMarkedAndOriginalArrivalTimeKept() {
        UsageStore store = new UsageStore();
        Instant cutoff = Instant.parse("2026-01-02T00:00:00Z");
        Instant lateArrival = Instant.parse("2026-01-03T12:00:00Z");
        store.ingest(new UsagePoint("t", "r", "cpu", 1L, 5.0, lateArrival, DataQuality.ON_TIME));

        List<UsagePoint> series = store.series("t", "r", "cpu", 1L, 1L, cutoff);
        assertEquals(DataQuality.LATE, series.get(0).quality());
        assertEquals(lateArrival, series.get(0).arrivedAt(), "必须保留原始到达时间");
    }

    @Test
    void estimationSkipsMissingAndLateWithDistinctReasons() {
        RuleStore rules = new RuleStore();
        rules.publish(new com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule(
                0, Instant.now(), Map.of("cpu", new BigDecimal("1.00")),
                BigDecimal.ZERO, 2, RoundingMode.HALF_UP, "v2"));
        UsageStore usageStore = new UsageStore();
        EstimationService estimation = new EstimationService(rules, new BillingEngine(), usageStore);

        Instant cutoff = Instant.parse("2026-01-02T00:00:00Z");
        Instant lateArrival = Instant.parse("2026-01-05T00:00:00Z");
        estimation.setLateCutoff(cutoff);

        // 周期 1：正常；周期 2：迟到；周期 3：缺失
        usageStore.ingest(new UsagePoint("t", "r", "cpu", 1L, 10.0,
                Instant.parse("2026-01-01T00:00:00Z"), DataQuality.ON_TIME));
        usageStore.ingest(new UsagePoint("t", "r", "cpu", 2L, 20.0, lateArrival, DataQuality.ON_TIME));

        CostEstimate est = estimation.estimate("t", "r", List.of(1L, 2L, 3L), null);
        assertEquals(EstimateStatus.PARTIAL, est.status());
        assertEquals(0, est.total().compareTo(new BigDecimal("10.00")), "只计入正常到达的周期");
        assertEquals(2, est.skipped().size());

        var late = est.skipped().stream().filter(s -> s.periodEpoch() == 2L).findFirst().orElseThrow();
        assertEquals(SkipReason.LATE_DATA, late.reason());
        assertEquals(lateArrival, late.arrivedAt(), "迟到项必须保留原始到达时间");

        var missing = est.skipped().stream().filter(s -> s.periodEpoch() == 3L).findFirst().orElseThrow();
        assertEquals(SkipReason.MISSING_DATA, missing.reason());
        assertNull(missing.arrivedAt());
    }
}
