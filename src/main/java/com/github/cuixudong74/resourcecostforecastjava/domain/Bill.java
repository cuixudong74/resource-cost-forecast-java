package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.time.Instant;

/**
 * 已结算账单。结算后不可变：后续规则变更不得改写历史账单，
 * 账单永远归属结算时的 ruleVersion。
 */
public record Bill(
        String billId,
        long ruleVersion,
        CostEstimate estimate,
        Instant settledAt) {
}
