package com.github.cuixudong74.resourcecostforecastjava.tests;

import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import com.github.cuixudong74.resourcecostforecastjava.domain.Bill;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import com.github.cuixudong74.resourcecostforecastjava.estimation.CostEstimator;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 规则版本切换：复算差异可解释，已结算账单不被改写。 */
class RuleVersionTest {

    private final RuleStore ruleStore = new RuleStore();
    private final UsageStore usageStore = new UsageStore();
    private final EstimationService estimationService = new EstimationService(
            usageStore, ruleStore, new CostEstimator(), new AppProperties());

    @Test
    void settledBillIsImmutableAcrossRuleChangesAndRecomputeIsExplainable() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plus(1, ChronoUnit.HOURS);
        usageStore.ingest(new UsageRecord("r1", "team-a", "cpu", start, end,
                10.0, "core-hour", start.plusSeconds(60), UsageStatus.ON_TIME));

        RuleSet v1 = ruleStore.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("2.00"), BigDecimal.ZERO)));
        CostEstimate estimateV1 = estimationService.estimate("team-a", "cpu", start, end, null);
        assertEquals(new BigDecimal("20.000000"), estimateV1.amount());
        assertEquals(1L, estimateV1.ruleVersion());

        Bill bill = estimationService.settle(estimateV1.estimateId());
        assertEquals(1L, bill.ruleVersion());

        // 规则变更：单价 2 -> 5
        RuleSet v2 = ruleStore.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("5.00"), BigDecimal.ZERO)));
        assertEquals(2L, v2.version());

        // 复算给出差异与解释
        EstimationService.VersionRecompute recompute =
                estimationService.recompute(bill.billId(), 2L);
        assertEquals(new BigDecimal("50.000000"), recompute.recomputed().amount());
        assertEquals(new BigDecimal("30.000000"), recompute.amountDelta());
        assertFalse(recompute.explanations().isEmpty());
        assertTrue(recompute.explanations().stream().anyMatch(e -> e.contains("unitPrice")));

        // 历史账单不被改写
        Bill reloaded = estimationService.bill(bill.billId()).orElseThrow();
        assertEquals(new BigDecimal("20.000000"), reloaded.estimate().amount());
        assertEquals(1L, reloaded.ruleVersion());

        // 同一估算重复结算幂等
        Bill again = estimationService.settle(estimateV1.estimateId());
        assertEquals(bill.billId(), again.billId());
    }

    @Test
    void sameUsageComputesUnderBothVersions() {
        Instant start = Instant.parse("2026-02-01T00:00:00Z");
        Instant end = start.plus(1, ChronoUnit.HOURS);
        usageStore.ingest(new UsageRecord("r2", "team-b", "mem", start, end,
                8.0, "gb-hour", start.plusSeconds(5), UsageStatus.ON_TIME));
        ruleStore.publish(List.of(
                new PricingRule("mem", "gb-hour", new BigDecimal("1.00"), new BigDecimal("2"))));
        ruleStore.publish(List.of(
                new PricingRule("mem", "gb-hour", new BigDecimal("3.00"), new BigDecimal("2"))));

        CostEstimate underV1 = estimationService.estimate("team-b", "mem", start, end, 1L);
        CostEstimate underV2 = estimationService.estimate("team-b", "mem", start, end, 2L);
        // (8-2)*1 = 6 ; (8-2)*3 = 18
        assertEquals(new BigDecimal("6.000000"), underV1.amount());
        assertEquals(new BigDecimal("18.000000"), underV2.amount());

        RuleSet.RuleDiff diff = ruleStore.version(1L).orElseThrow()
                .diff(ruleStore.version(2L).orElseThrow());
        assertEquals(1, diff.changed().size());
        assertTrue(diff.changed().get(0).explain().contains("1.00 -> 3.00"));
    }
}
