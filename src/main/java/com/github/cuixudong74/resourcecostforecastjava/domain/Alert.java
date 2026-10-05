package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 预算告警。alertKey = budgetId + period + threshold，用于去重：
 * 同一阈值同一周期只告警一次（不漏不重）。reason 给出触发依据。
 */
public record Alert(
        String alertKey,
        String budgetId,
        String teamId,
        String resourceId,
        long periodEpoch,
        double threshold,
        BigDecimal actual,
        BigDecimal budgetAmount,
        String reason,
        Instant firedAt) {
}
