package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预算配置：按团队 + 资源（resourceId 为 null 表示团队全部资源）。
 * thresholds 为触发告警的比例阈值（如 0.8、1.0）。
 */
public record Budget(
        String budgetId,
        String teamId,
        String resourceId,
        BigDecimal limitAmount,
        List<Double> thresholds) {

    public Budget {
        thresholds = List.copyOf(thresholds);
    }
}
