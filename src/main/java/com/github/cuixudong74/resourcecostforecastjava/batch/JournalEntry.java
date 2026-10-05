package com.github.cuixudong74.resourcecostforecastjava.batch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;

import java.util.List;

/**
 * 提交日志条目。
 * kind=START：批次开始，记录完整参数，供重启后恢复；
 * kind=COMMIT：一个周期的估算已提交，携带结果与已提交计数。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JournalEntry(
        String kind,
        String batchId,
        String teamId,
        String resourceId,
        List<Long> periods,
        Long ruleVersion,
        Integer committedCount,
        CostEstimate estimate) {

    public static JournalEntry start(String batchId, String teamId, String resourceId,
                                     List<Long> periods, long ruleVersion) {
        return new JournalEntry("START", batchId, teamId, resourceId, periods, ruleVersion, null, null);
    }

    public static JournalEntry commit(String batchId, int committedCount, CostEstimate estimate) {
        return new JournalEntry("COMMIT", batchId, null, null, null, null, committedCount, estimate);
    }
}
