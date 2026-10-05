package com.github.cuixudong74.resourcecostforecastjava.batch;

import java.time.Instant;

/** 一个估算批次任务。jobId 幂等：重复触发收敛到同一结果。 */
public record BatchJob(
        String jobId,
        String teamId,
        String resourceId,
        Instant periodStart,
        Instant periodEnd,
        Long ruleVersion) {
}
