package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 用量数据质量：正常到达 / 迟到到达 / 缺失（占位，值必须为 null，不得用 0 冒充）。 */
public enum DataQuality {
    ON_TIME,
    LATE,
    MISSING
}
