package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.util.List;

/** 预测结果，记录所用策略、种子与规则版本，保证可复现、可追溯。 */
public record ForecastResult(
        String teamId,
        String resourceId,
        ForecastStrategy strategy,
        long seed,
        long ruleVersion,
        List<ForecastPoint> points) {

    public ForecastResult {
        points = List.copyOf(points);
    }
}
