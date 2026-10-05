package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.time.Instant;

/** 被跳过的用量及原因，保留原始到达时间。 */
public record SkippedUsage(String recordId, SkipReason reason, Instant arrivedAt) {
}
