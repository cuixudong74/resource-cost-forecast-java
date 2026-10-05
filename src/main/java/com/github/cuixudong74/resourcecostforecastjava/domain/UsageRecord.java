package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.time.Instant;

/**
 * 一条资源用量记录。
 * arrivedAt 永远保留原始到达时间；status 区分按时/迟到/缺失。
 */
public record UsageRecord(
        String recordId,
        String teamId,
        String resourceId,
        Instant periodStart,
        Instant periodEnd,
        double quantity,
        String unit,
        Instant arrivedAt,
        UsageStatus status) {

    public UsageRecord {
        if (status == UsageStatus.MISSING && quantity != 0.0d) {
            throw new IllegalArgumentException("MISSING record must not carry quantity");
        }
    }
}
