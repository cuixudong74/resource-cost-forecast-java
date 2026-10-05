package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 用量记录的到达状态。 */
public enum UsageStatus {
    /** 在统计截止前按时到达 */
    ON_TIME,
    /** 迟到到达（到达时间晚于所属账期截止），仍保留原始到达时间 */
    LATE,
    /** 缺失占位：账期结束仍未收到数据，quantity 不得按 0 计入 */
    MISSING
}
