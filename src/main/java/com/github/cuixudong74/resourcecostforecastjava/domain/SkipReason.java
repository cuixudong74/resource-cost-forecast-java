package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 估算/预测时跳过某条用量的原因，便于区分缺失与迟到。 */
public enum SkipReason {
    NONE,
    /** 账期结束数据仍未到达，按缺失跳过（不补零） */
    MISSING_DATA,
    /** 到达时间晚于账期允许的最晚入账时间 */
    LATE_BEYOND_GRACE,
    /** 重复记录（按 recordId 去重） */
    DUPLICATE_RECORD
}
