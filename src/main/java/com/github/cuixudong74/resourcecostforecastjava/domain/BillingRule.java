package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

/**
 * 计费规则的不可变版本。发布后内容不得修改；
 * 读者任一时刻只能看到某个完整版本（整体原子替换）。
 */
public record BillingRule(
        long version,
        Instant createdAt,
        Map<String, BigDecimal> unitPrices,
        BigDecimal freeTierPerResource,
        int scale,
        RoundingMode roundingMode,
        String description) {

    public BillingRule {
        unitPrices = Map.copyOf(unitPrices);
    }

    public BigDecimal unitPriceOf(String metric) {
        return unitPrices.getOrDefault(metric, BigDecimal.ZERO);
    }
}
