package com.github.cuixudong74.resourcecostforecastjava.batch;

/** 批次状态：已提交结果与未完成批次可区分。 */
public enum BatchStatus {
    PENDING,
    RUNNING,
    COMMITTED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
