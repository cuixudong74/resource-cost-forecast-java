package com.github.cuixudong74.resourcecostforecastjava.rules;

import java.math.BigDecimal;
import java.util.List;

/**
 * 两个规则版本之间的可解释差异：
 * 同一用量在两个版本下复算，逐项给出价格变化与成本差。
 */
public record RuleDiff(
        long fromVersion,
        long toVersion,
        List<Entry> entries,
        BigDecimal totalDelta) {

    public record Entry(
            String metric,
            BigDecimal oldUnitPrice,
            BigDecimal newUnitPrice,
            double quantity,
            BigDecimal oldCost,
            BigDecimal newCost,
            BigDecimal delta) {
    }
}
