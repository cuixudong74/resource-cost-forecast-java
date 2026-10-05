package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 预算告警。alertKey 用于去重（同一预算+阈值+账期只告警一次），
 * basis 给出触发依据。
 */
public record Alert(
        String alertKey,
        String budgetId,
        String teamId,
        String resourceId,
        double threshold,
        BigDecimal actualAmount,
        BigDecimal limitAmount,
        long ruleVersion,
        String basis,
        Instant raisedAt) {
}
