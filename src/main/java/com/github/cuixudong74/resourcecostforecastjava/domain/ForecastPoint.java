package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** 单个未来账期的预测点。 */
public record ForecastPoint(
        Instant periodStart,
        double expectedQuantity,
        BigDecimal expectedCost) {
}
