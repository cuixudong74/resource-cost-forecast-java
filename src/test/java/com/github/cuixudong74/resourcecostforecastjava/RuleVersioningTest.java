package com.github.cuixudong74.resourcecostforecastjava;

import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality;
import com.github.cuixudong74.resourcecostforecastjava.domain.SettledBill;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.estimation.UsageStore;
import com.github.cuixudong74.resourcecostforecastjava.rules.BillingEngine;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleDiff;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleDiffService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则版本切换：同一份用量在不同版本下复算，差异可解释；已结算账单不被改写。 */
class RuleVersioningTest {

    private static BillingRule draft(String cpuPrice, String desc) {
        return new BillingRule(0, Instant.now(),
                Map.of("cpu", new BigDecimal(cpuPrice)),
                BigDecimal.ZERO, 2, RoundingMode.HALF_UP, desc);
    }

    @Test
    void sameUsageRecomputedUnderBothVersionsWithExplainableDiff() {
        RuleStore rules = new RuleStore();
        BillingEngine engine = new BillingEngine();
        UsageStore usageStore = new UsageStore();
        EstimationService estimation = new EstimationService(rules, engine, usageStore);
        RuleDiffService diffService = new RuleDiffService(rules, engine);

        rules.publish(draft("0.10", "v2-cheap"));   // v2
        long v2 = rules.current().version();

        usageStore.ingest(new UsagePoint("team-a", "res-1", "cpu", 1L, 100.0,
                Instant.now(), DataQuality.ON_TIME));

        CostEstimate underV2 = estimation.estimate("team-a", "res-1", List.of(1L), v2);
        assertEquals(0, underV2.total().compareTo(new BigDecimal("10.00")));
        assertEquals(v2, underV2.ruleVersion());

        rules.publish(draft("0.50", "v3-expensive")); // v3
        long v3 = rules.current().version();

        CostEstimate underV3 = estimation.estimate("team-a", "res-1", List.of(1L), v3);
        assertEquals(0, underV3.total().compareTo(new BigDecimal("50.00")));
        assertEquals(v3, underV3.ruleVersion());

        // 差异可解释：逐项给出单价与成本变化
        List<UsagePoint> usage = usageStore.series("team-a", "res-1", "cpu", 1L, 1L, null);
        RuleDiff diff = diffService.diff(v2, v3, usage);
        assertEquals(1, diff.entries().size());
        RuleDiff.Entry entry = diff.entries().get(0);
        assertEquals("cpu", entry.metric());
        assertEquals(0, entry.oldUnitPrice().compareTo(new BigDecimal("0.10")));
        assertEquals(0, entry.newUnitPrice().compareTo(new BigDecimal("0.50")));
        assertEquals(0, entry.delta().compareTo(new BigDecimal("40.00")));
        assertEquals(0, diff.totalDelta().compareTo(new BigDecimal("40.00")));
    }

    @Test
    void settledBillIsNotRewrittenByLaterRuleChanges() {
        RuleStore rules = new RuleStore();
        BillingEngine engine = new BillingEngine();
        UsageStore usageStore = new UsageStore();
        EstimationService estimation = new EstimationService(rules, engine, usageStore);

        rules.publish(draft("0.10", "v2"));
        usageStore.ingest(new UsagePoint("team-a", "res-1", "cpu", 7L, 100.0,
                Instant.now(), DataQuality.ON_TIME));

        SettledBill bill = estimation.settle("team-a", "res-1", 7L);
        assertEquals(0, bill.amount().compareTo(new BigDecimal("10.00")));
        long settledRuleVersion = bill.ruleVersion();

        // 规则变更后，结算账单不变
        rules.publish(draft("9.99", "v3-price-hike"));
        SettledBill after = estimation.settledBill("team-a", "res-1", 7L).orElseThrow();
        assertEquals(0, after.amount().compareTo(new BigDecimal("10.00")));
        assertEquals(settledRuleVersion, after.ruleVersion());

        // 重复结算幂等，返回同一账单
        SettledBill again = estimation.settle("team-a", "res-1", 7L);
        assertEquals(bill.billId(), again.billId());
        assertEquals(0, again.amount().compareTo(new BigDecimal("10.00")));

        // 已结算周期参与新估算时引用冻结金额而非新规则
        CostEstimate est = estimation.estimate("team-a", "res-1", List.of(7L), null);
        assertEquals(0, est.total().compareTo(new BigDecimal("10.00")));
        assertTrue(est.lines().stream().anyMatch(l -> l.metric().equals("settled")));
    }
}
