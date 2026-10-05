package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 计算批次。已提交位置（committedCount）之前的估算已持久化，
 * 重启后从 committedCount 继续；未提交部分与已提交结果可区分。
 */
public record BatchJob(
        String batchId,
        List<Long> periods,
        int committedCount,
        BatchStatus status,
        long ruleVersion,
        Instant createdAt,
        Instant updatedAt) {

    public BatchJob {
        periods = List.copyOf(periods);
    }

    public BatchJob withProgress(int newCommittedCount, BatchStatus newStatus, Instant now) {
        return new BatchJob(batchId, periods, newCommittedCount, newStatus, ruleVersion, createdAt, now);
    }
}
