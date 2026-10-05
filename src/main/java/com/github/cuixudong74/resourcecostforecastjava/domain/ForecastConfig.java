package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 预测配置：窗口长度、策略、外推步数、随机种子（相同输入+种子必须复现）。 */
public record ForecastConfig(
        int window,
        ForecastStrategy strategy,
        int horizon,
        long seed) {
}
