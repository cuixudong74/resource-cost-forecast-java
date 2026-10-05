package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 一次成本估算结果。estimateId 由输入确定性生成，重复触发收敛到同一结果。
 * ruleVersion 记录归属的规则版本，历史可追溯。
 */
public record CostEstimate(
        String estimateId,
        long ruleVersion,
        List<CostLineItem> lines,
        List<SkippedItem> skipped,
        BigDecimal total,
        EstimateStatus status,
        Instant computedAt) {

    public CostEstimate {
        lines = List.copyOf(lines);
        skipped = List.copyOf(skipped);
    }
}
