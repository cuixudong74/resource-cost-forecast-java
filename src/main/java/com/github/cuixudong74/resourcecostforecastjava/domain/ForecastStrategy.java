package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 预测策略。 */
public enum ForecastStrategy {
    /** 窗口内移动平均 */
    MOVING_AVERAGE,
    /** 窗口内最小二乘线性趋势外推 */
    LINEAR_TREND
}
