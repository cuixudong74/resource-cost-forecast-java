package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;

/** 一条可解释的成本明细：用量 × 单价 = 成本。 */
public record CostLineItem(
        String teamId,
        String resourceId,
        String metric,
        long periodEpoch,
        double quantity,
        BigDecimal unitPrice,
        BigDecimal cost) {
}
