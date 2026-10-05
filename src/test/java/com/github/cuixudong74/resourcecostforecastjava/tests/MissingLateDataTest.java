package com.github.cuixudong74.resourcecostforecastjava.tests;

import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import com.github.cuixudong74.resourcecostforecastjava.estimation.CostEstimator;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import com.github.cuixudong74.resourcecostforecastjava.usage.IngestResult;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 缺失与迟到数据：区分跳过原因、保留原始到达时间、不用零值冒充。 */
class MissingLateDataTest {

    private final RuleStore ruleStore = new RuleStore();
    private final UsageStore usageStore = new UsageStore();
    private final AppProperties properties = new AppProperties();
    private final EstimationService estimationService = new EstimationService(
            usageStore, ruleStore, new CostEstimator(), properties);

    private static final Instant P1_START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant P1_END = P1_START.plus(1, ChronoUnit.HOURS);
    private static final Instant P2_START = P1_END;
    private static final Instant P2_END = P2_START.plus(1, ChronoUnit.HOURS);

    @Test
    void missingDataIsSkippedNotZeroFilled() {
        ruleStore.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("2.00"), BigDecimal.ZERO)));
        // 账期1正常到达，账期2缺失占位
        usageStore.ingest(new UsageRecord("ok-1", "team-a", "cpu", P1_START, P1_END,
                5.0, "core-hour", P1_START.plusSeconds(10), UsageStatus.ON_TIME));
        usageStore.markMissing("gap-2", "team-a", "cpu", P2_START, P2_END, "core-hour");

        CostEstimate estimate = estimationService.estimate(
                "team-a", "cpu", P1_START, P2_END, null);
        // 只计账期1：5*2=10；缺失账期不得按 0 计入 billedQuantity
        assertEquals(new BigDecimal("10.000000"), estimate.amount());
        assertEquals(5.0, estimate.billedQuantity());
        assertEquals(1, estimate.skipped().size());
        assertEquals("gap-2", estimate.skipped().get(0).recordId());
        assertEquals(SkipReason.MISSING_DATA, estimate.skipped().get(0).reason());
    }

    @Test
    void lateDataKeepsOriginalArrivalAndGraceIsRespected() {
        properties.setLateGraceMillis(60_000); // 宽限 60s
        ruleStore.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("2.00"), BigDecimal.ZERO)));

        // 宽限内迟到：计入
        Instant lateWithinGrace = P1_END.plusSeconds(30);
        IngestResult r1 = usageStore.ingest(new UsageRecord("late-ok", "team-a", "cpu",
                P1_START, P1_END, 3.0, "core-hour", lateWithinGrace, UsageStatus.ON_TIME));
        assertTrue(r1.accepted());
        // 状态被标记为 LATE，原始到达时间保留
        UsageRecord stored = usageStore.query("team-a", "cpu", P1_START, P1_END).get(0);
        assertEquals(UsageStatus.LATE, stored.status());
        assertEquals(lateWithinGrace, stored.arrivedAt());

        // 超出宽限的迟到：跳过并注明原因
        Instant lateBeyondGrace = P1_END.plusSeconds(3600);
        usageStore.ingest(new UsageRecord("late-skip", "team-a", "cpu",
                P1_START, P1_END, 100.0, "core-hour", lateBeyondGrace, UsageStatus.ON_TIME));

        CostEstimate estimate = estimationService.estimate(
                "team-a", "cpu", P1_START, P1_END, null);
        // 只计宽限内的 3*2=6
        assertEquals(new BigDecimal("6.000000"), estimate.amount());
        assertEquals(1, estimate.skipped().size());
        assertEquals("late-skip", estimate.skipped().get(0).recordId());
        assertEquals(SkipReason.LATE_BEYOND_GRACE, estimate.skipped().get(0).reason());
        // 跳过记录保留原始到达时间
        assertEquals(lateBeyondGrace, estimate.skipped().get(0).arrivedAt());
    }

    @Test
    void lateArrivalFillsMissingPlaceholderAndDuplicatesAreRejected() {
        usageStore.markMissing("rec-1", "team-a", "cpu", P1_START, P1_END, "core-hour");
        // 迟到数据补登：替换占位
        IngestResult filled = usageStore.ingest(new UsageRecord("rec-1", "team-a", "cpu",
                P1_START, P1_END, 7.0, "core-hour", P1_END.plusSeconds(5), UsageStatus.ON_TIME));
        assertTrue(filled.accepted());
        List<UsageRecord> records = usageStore.query("team-a", "cpu", P1_START, P1_END);
        assertEquals(1, records.size());
        assertEquals(UsageStatus.LATE, records.get(0).status());
        assertEquals(7.0, records.get(0).quantity());

        // 重复摄入幂等去重
        IngestResult dup = usageStore.ingest(new UsageRecord("rec-1", "team-a", "cpu",
                P1_START, P1_END, 999.0, "core-hour", P1_END.plusSeconds(6), UsageStatus.ON_TIME));
        assertFalse(dup.accepted());
        assertEquals(SkipReason.DUPLICATE_RECORD, dup.reason());
        assertEquals(7.0, usageStore.query("team-a", "cpu", P1_START, P1_END).get(0).quantity());
    }
}
