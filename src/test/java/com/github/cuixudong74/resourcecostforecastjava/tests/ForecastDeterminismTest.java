package com.github.cuixudong74.resourcecostforecastjava.tests;

import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastConfig;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastResult;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastStrategy;
import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import com.github.cuixudong74.resourcecostforecastjava.forecast.Forecaster;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 预测：相同输入 + 相同种子 => 逐次复现；缺失账期不参与拟合。 */
class ForecastDeterminismTest {

    private final Forecaster forecaster = new Forecaster();

    private RuleSet ruleSet() {
        RuleStore store = new RuleStore();
        store.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("2.00"), BigDecimal.ZERO)));
        return store.current();
    }

    private List<UsageRecord> history() {
        Instant base = Instant.parse("2026-07-01T00:00:00Z");
        List<UsageRecord> records = new ArrayList<>();
        double[] quantities = {10, 12, 11, 13, 12, 14, 13};
        for (int i = 0; i < quantities.length; i++) {
            Instant start = base.plus(i, ChronoUnit.DAYS);
            records.add(new UsageRecord("r" + i, "team-a", "cpu", start,
                    start.plus(1, ChronoUnit.DAYS), quantities[i], "core-hour",
                    start.plusSeconds(30), UsageStatus.ON_TIME));
        }
        // 缺失占位：不参与拟合
        Instant gapStart = base.plus(7, ChronoUnit.DAYS);
        records.add(new UsageRecord("gap", "team-a", "cpu", gapStart,
                gapStart.plus(1, ChronoUnit.DAYS), 0.0, "core-hour", null, UsageStatus.MISSING));
        return records;
    }

    @Test
    void sameSeedReproducesIdenticalResult() {
        ForecastConfig config = new ForecastConfig(5, ForecastStrategy.MOVING_AVERAGE, 4, 99L);
        Instant futureStart = Instant.parse("2026-07-09T00:00:00Z");
        long dayMillis = ChronoUnit.DAYS.getDuration().toMillis();

        ForecastResult first = forecaster.forecast("team-a", "cpu", history(),
                config, ruleSet(), futureStart, dayMillis);
        ForecastResult second = forecaster.forecast("team-a", "cpu", history(),
                config, ruleSet(), futureStart, dayMillis);
        assertEquals(first, second, "same input + same seed must reproduce identical result");
        assertEquals(4, first.points().size());
        assertEquals(99L, first.seed());
    }

    @Test
    void differentSeedsGenerallyDifferAndStrategiesWork() {
        Instant futureStart = Instant.parse("2026-07-09T00:00:00Z");
        long dayMillis = ChronoUnit.DAYS.getDuration().toMillis();

        ForecastResult seedA = forecaster.forecast("team-a", "cpu", history(),
                new ForecastConfig(5, ForecastStrategy.MOVING_AVERAGE, 4, 1L),
                ruleSet(), futureStart, dayMillis);
        ForecastResult seedB = forecaster.forecast("team-a", "cpu", history(),
                new ForecastConfig(5, ForecastStrategy.MOVING_AVERAGE, 4, 2L),
                ruleSet(), futureStart, dayMillis);
        assertNotEquals(seedA.points(), seedB.points());

        ForecastResult trend = forecaster.forecast("team-a", "cpu", history(),
                new ForecastConfig(7, ForecastStrategy.LINEAR_TREND, 3, 42L),
                ruleSet(), futureStart, dayMillis);
        assertEquals(3, trend.points().size());
        // 上升趋势序列的线性外推应高于窗口均值(约12.14)
        assertTrue(trend.points().get(0).expectedQuantity() > 12.0);
        // 成本 = 预测量 * 单价2
        assertEquals(0, trend.points().get(0).expectedCost().compareTo(
                BigDecimal.valueOf(trend.points().get(0).expectedQuantity())
                        .multiply(new BigDecimal("2.00"))
                        .setScale(6, java.math.RoundingMode.HALF_UP)));
    }
}
