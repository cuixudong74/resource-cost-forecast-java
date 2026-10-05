package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 已结算账单：结算后不可变，后续规则变更不得改写。 */
public record SettledBill(
        String billId,
        String teamId,
        String resourceId,
        long periodEpoch,
        long ruleVersion,
        BigDecimal amount,
        Instant settledAt) {
}
