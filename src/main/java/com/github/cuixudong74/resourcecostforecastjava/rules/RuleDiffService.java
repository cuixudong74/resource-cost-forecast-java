package com.github.cuixudong74.resourcecostforecastjava.rules;

import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用同一份用量在两个规则版本下复算，逐项给出单价变化与成本差，
 * 使“计费规则一改历史账单对不上”可解释。
 */
@Component
public class RuleDiffService {

    private final RuleStore ruleStore;
    private final BillingEngine engine;

    public RuleDiffService(RuleStore ruleStore, BillingEngine engine) {
        this.ruleStore = ruleStore;
        this.engine = engine;
    }

    public RuleDiff diff(long fromVersion, long toVersion, List<UsagePoint> usage) {
        BillingRule from = ruleStore.get(fromVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version: " + fromVersion));
        BillingRule to = ruleStore.get(toVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version: " + toVersion));

        // 按指标聚合用量，逐指标对比两个版本下的成本。
        Map<String, Double> quantityByMetric = new LinkedHashMap<>();
        for (UsagePoint p : usage) {
            if (!p.isMissing()) {
                quantityByMetric.merge(p.metric(), p.value(), Double::sum);
            }
        }

        List<RuleDiff.Entry> entries = new ArrayList<>();
        BigDecimal totalDelta = BigDecimal.ZERO;
        for (Map.Entry<String, Double> e : quantityByMetric.entrySet()) {
            String metric = e.getKey();
            double qty = e.getValue();
            UsagePoint probe = new UsagePoint("-", "-", metric, 0L, qty, null,
                    com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality.ON_TIME);
            BigDecimal oldCost = engine.priceOf(from, probe);
            BigDecimal newCost = engine.priceOf(to, probe);
            BigDecimal delta = newCost.subtract(oldCost);
            entries.add(new RuleDiff.Entry(metric,
                    from.unitPriceOf(metric), to.unitPriceOf(metric),
                    qty, oldCost, newCost, delta));
            totalDelta = totalDelta.add(delta);
        }
        return new RuleDiff(fromVersion, toVersion, entries, totalDelta);
    }
}
