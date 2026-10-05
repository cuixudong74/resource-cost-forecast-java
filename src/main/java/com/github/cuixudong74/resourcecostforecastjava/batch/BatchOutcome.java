package com.github.cuixudong74.resourcecostforecastjava.batch;

import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;

import java.time.Instant;

/** 批次执行结果记录。 */
public record BatchOutcome(
        String jobId,
        BatchStatus status,
        CostEstimate estimate,
        String detail,
        Instant updatedAt) {
}
