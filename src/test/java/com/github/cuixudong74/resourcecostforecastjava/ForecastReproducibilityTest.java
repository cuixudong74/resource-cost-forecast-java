package com.github.cuixudong74.resourcecostforecastjava;

import com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastConfig;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastResult;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastStrategy;
import com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.estimation.UsageStore;
import com.github.cuixudong74.resourcecostforecastjava.forecast.ForecastService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预测：相同输入 + 相同种子 => 逐次复现；缺失/迟到周期被跳过且原因可区分。 */
class ForecastReproducibilityTest {

    private UsageStore storeWithSeries(int n) {
        UsageStore store = new UsageStore();
        IntStream.rangeClosed(1, n).forEach(p ->
                store.ingest(new UsagePoint("t", "r", "cpu", p, 100.0 + p * 2.5,
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(p), DataQuality.ON_TIME)));
        return store;
    }

    @Test
    void sameInputSameSeedReproducesExactly() {
        ForecastConfig config = new ForecastConfig(5, 7, ForecastStrategy.LINEAR_TREND, 42L, 300);
        ForecastResult first = new ForecastService(storeWithSeries(30))
                .forecast("t", "r", "cpu", 1L, 30L, config);
        ForecastResult second = new ForecastService(storeWithSeries(30))
                .forecast("t", "r", "cpu", 1L, 30L, config);
        assertEquals(first, second, "相同输入与种子必须逐次复现");
        assertEquals(7, first.points().size());
    }

    @Test
    void differentSeedGivesDifferentIntervals() {
        UsageStore store = storeWithSeries(30);
        ForecastService service = new ForecastService(store);
        ForecastResult a = service.forecast("t", "r", "cpu", 1L, 30L,
                new ForecastConfig(5, 7, ForecastStrategy.MOVING_AVERAGE, 1L, 300));
        ForecastResult b = service.forecast("t", "r", "cpu", 1L, 30L,
                new ForecastConfig(5, 7, ForecastStrategy.MOVING_AVERAGE, 2L, 300));
        // 点预测相同（确定性部分），随机区间不同
        assertEquals(a.points().get(0).mean(), b.points().get(0).mean());
        assertNotEquals(a.points().get(6).p90(), b.points().get(6).p90());
    }

    @Test
    void missingAndLatePeriodsAreSkippedWithReasons() {
        UsageStore store = storeWithSeries(10);
        // 覆盖掉周期 3：迟到到达
        Instant lateArrival = Instant.parse("2026-02-01T00:00:00Z");
        UsageStore store2 = new UsageStore();
        IntStream.rangeClosed(1, 10).forEach(p -> {
            if (p == 3) {
                store2.ingest(new UsagePoint("t", "r", "cpu", p, 100.0 + p * 2.5,
                        lateArrival, DataQuality.ON_TIME));
            } else if (p != 7) { // 周期 7 缺失（不上报）
                store2.ingest(new UsagePoint("t", "r", "cpu", p, 100.0 + p * 2.5,
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(p), DataQuality.ON_TIME));
            }
        });
        ForecastService service = new ForecastService(store2);
        ForecastResult result = service.forecast("t", "r", "cpu", 1L, 10L,
                ForecastConfig.defaults(ForecastStrategy.HOLT_DOUBLE_EXPONENTIAL, 7L),
                Instant.parse("2026-01-15T00:00:00Z"));

        assertEquals(2, result.skipped().size());
        var late = result.skipped().stream().filter(s -> s.periodEpoch() == 3L).findFirst().orElseThrow();
        assertEquals(SkipReason.LATE_DATA, late.reason());
        assertEquals(lateArrival, late.arrivedAt());
        var missing = result.skipped().stream().filter(s -> s.periodEpoch() == 7L).findFirst().orElseThrow();
        assertEquals(SkipReason.MISSING_DATA, missing.reason());
        assertTrue(result.points().size() > 0, "跳过缺失后仍应基于剩余数据外推");
    }
}
