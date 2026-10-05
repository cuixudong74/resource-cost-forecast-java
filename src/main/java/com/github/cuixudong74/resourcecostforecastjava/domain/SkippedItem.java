package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.time.Instant;

/** 被跳过的用量项，保留原因与原始到达时间。 */
public record SkippedItem(
        String resourceId,
        String metric,
        long periodEpoch,
        SkipReason reason,
        Instant arrivedAt) {
}
