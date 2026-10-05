package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 单步预测结果：均值与分位数区间。 */
public record ForecastPoint(
        long periodEpoch,
        double mean,
        double p10,
        double p90) {
}
