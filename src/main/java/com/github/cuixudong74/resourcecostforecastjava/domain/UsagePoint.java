package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.time.Instant;

/**
 * 单条用量点。value 为 null 表示缺失（MISSING），不得用 0 代替。
 * arrivedAt 保留原始到达时间，用于迟到判定与审计。
 */
public record UsagePoint(
        String teamId,
        String resourceId,
        String metric,
        long periodEpoch,
        Double value,
        Instant arrivedAt,
        DataQuality quality) {

    public boolean isMissing() {
        return quality == DataQuality.MISSING || value == null;
    }
}
