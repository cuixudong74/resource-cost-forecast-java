package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.util.List;

/** 预测输出。输入与种子相同时必须逐字节可复现；skipped 记录被跳过周期及原因。 */
public record ForecastResult(
        ForecastConfig config,
        List<ForecastPoint> points,
        List<SkippedItem> skipped,
        long seed) {

    public ForecastResult {
        points = List.copyOf(points);
        skipped = List.copyOf(skipped);
    }
}
