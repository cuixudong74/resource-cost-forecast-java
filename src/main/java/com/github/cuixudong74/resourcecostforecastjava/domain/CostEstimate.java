package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 一次成本估算结果。ruleVersion 记录估算归属的规则版本，可追溯。
 */
public record CostEstimate(
        String estimateId,
        String teamId,
        String resourceId,
        long ruleVersion,
        Instant periodStart,
        Instant periodEnd,
        double billedQuantity,
        BigDecimal amount,
        List<CostLineItem> items,
        List<SkippedUsage> skipped,
        Instant computedAt) {

    public CostEstimate {
        items = List.copyOf(items);
        skipped = List.copyOf(skipped);
    }
}
