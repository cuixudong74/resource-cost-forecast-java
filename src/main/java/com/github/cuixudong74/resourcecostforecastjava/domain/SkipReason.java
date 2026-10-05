package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 估算/预测中跳过某个周期的原因。 */
public enum SkipReason {
    MISSING_DATA,
    LATE_DATA,
    CANCELLED
}
