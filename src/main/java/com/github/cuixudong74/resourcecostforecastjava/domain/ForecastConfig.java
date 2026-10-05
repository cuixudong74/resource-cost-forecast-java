package com.github.cuixudong74.resourcecostforecastjava.domain;

/** 预测配置：窗口、外推步数、策略、随机种子与模拟次数（种子相同则结果可复现）。 */
public record ForecastConfig(
        int window,
        int horizon,
        ForecastStrategy strategy,
        long seed,
        int simulations) {

    public static ForecastConfig defaults(ForecastStrategy strategy, long seed) {
        return new ForecastConfig(7, 7, strategy, seed, 200);
    }
}
