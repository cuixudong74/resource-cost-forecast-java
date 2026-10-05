package com.github.cuixudong74.resourcecostforecastjava.forecast;

import com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastConfig;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastPoint;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastResult;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.estimation.UsageStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 预测服务。
 *
 * 可复现性：所有随机性都来自 new Random(config.seed())，且不使用任何
 * 依赖时钟或哈希迭代顺序的逻辑；相同输入 + 相同种子 => 逐次结果一致。
 *
 * 数据质量：MISSING 与 LATE 的周期不参与训练，记录到 skippedPeriods，
 * 绝不用 0 填充缺失值。
 */
@Service
public class ForecastService {

    private final UsageStore usageStore;

    public ForecastService(UsageStore usageStore) {
        this.usageStore = usageStore;
    }

    public ForecastResult forecast(String teamId, String resourceId, String metric,
                                   long fromPeriod, long toPeriod, ForecastConfig config) {
        return forecast(teamId, resourceId, metric, fromPeriod, toPeriod, config, null);
    }

    /**
     * lateCutoff 非空时，到达时间晚于它的点按 LATE 跳过（保留原始到达时间）。
     */
    public ForecastResult forecast(String teamId, String resourceId, String metric,
                                   long fromPeriod, long toPeriod, ForecastConfig config,
                                   java.time.Instant lateCutoff) {
        List<UsagePoint> series = usageStore.series(teamId, resourceId, metric, fromPeriod, toPeriod, lateCutoff);
        List<Double> values = new ArrayList<>();
        List<com.github.cuixudong74.resourcecostforecastjava.domain.SkippedItem> skipped = new ArrayList<>();
        for (UsagePoint p : series) {
            if (p.quality() == DataQuality.ON_TIME) {
                values.add(p.value());
            } else {
                var reason = p.quality() == DataQuality.LATE
                        ? com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason.LATE_DATA
                        : com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason.MISSING_DATA;
                skipped.add(new com.github.cuixudong74.resourcecostforecastjava.domain.SkippedItem(
                        resourceId, metric, p.periodEpoch(), reason, p.arrivedAt()));
            }
        }
        if (values.isEmpty()) {
            return new ForecastResult(config, List.of(), skipped, config.seed());
        }

        double[] history = values.stream().mapToDouble(Double::doubleValue).toArray();
        double[] pointForecast = switch (config.strategy()) {
            case MOVING_AVERAGE -> movingAverage(history, config.window(), config.horizon());
            case LINEAR_TREND -> linearTrend(history, config.horizon());
            case HOLT_DOUBLE_EXPONENTIAL -> holt(history, config.horizon());
        };

        // 蒙特卡洛区间：以历史残差标准差为噪声幅度，种子固定 => 可复现。
        Random random = new Random(config.seed());
        double sigma = residualStd(history, config);
        int sims = Math.max(1, config.simulations());
        List<ForecastPoint> points = new ArrayList<>();
        for (int h = 0; h < config.horizon(); h++) {
            double mean = pointForecast[h];
            double[] samples = new double[sims];
            for (int s = 0; s < sims; s++) {
                samples[s] = mean + sigma * Math.sqrt(h + 1) * random.nextGaussian();
            }
            java.util.Arrays.sort(samples);
            double p10 = samples[(int) (0.10 * (sims - 1))];
            double p90 = samples[(int) (0.90 * (sims - 1))];
            points.add(new ForecastPoint(toPeriod + h + 1, mean, p10, p90));
        }
        return new ForecastResult(config, points, skipped, config.seed());
    }

    private static double[] movingAverage(double[] history, int window, int horizon) {
        double[] out = new double[horizon];
        double[] extended = java.util.Arrays.copyOf(history, history.length + horizon);
        for (int h = 0; h < horizon; h++) {
            int end = history.length + h;
            int start = Math.max(0, end - window);
            double sum = 0;
            for (int i = start; i < end; i++) {
                sum += extended[i];
            }
            double v = sum / (end - start);
            extended[end] = v;
            out[h] = v;
        }
        return out;
    }

    private static double[] linearTrend(double[] history, int horizon) {
        int n = history.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sx += i;
            sy += history[i];
            sxx += (double) i * i;
            sxy += i * history[i];
        }
        double denom = n * sxx - sx * sx;
        double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
        double intercept = (sy - slope * sx) / n;
        double[] out = new double[horizon];
        for (int h = 0; h < horizon; h++) {
            out[h] = intercept + slope * (n + h);
        }
        return out;
    }

    private static double[] holt(double[] history, int horizon) {
        double alpha = 0.5, beta = 0.1;
        double level = history[0];
        double trend = history.length > 1 ? history[1] - history[0] : 0;
        for (int i = 1; i < history.length; i++) {
            double prevLevel = level;
            level = alpha * history[i] + (1 - alpha) * (level + trend);
            trend = beta * (level - prevLevel) + (1 - beta) * trend;
        }
        double[] out = new double[horizon];
        for (int h = 0; h < horizon; h++) {
            out[h] = level + (h + 1) * trend;
        }
        return out;
    }

    private static double residualStd(double[] history, ForecastConfig config) {
        if (history.length < 3) {
            return 0.0;
        }
        double[] fitted = switch (config.strategy()) {
            case MOVING_AVERAGE -> {
                double[] f = new double[history.length];
                for (int i = 0; i < history.length; i++) {
                    int start = Math.max(0, i - config.window());
                    double sum = 0;
                    for (int j = start; j < i; j++) sum += history[j];
                    f[i] = i == start ? history[i] : sum / (i - start);
                }
                yield f;
            }
            case LINEAR_TREND -> {
                int n = history.length;
                double sx = 0, sy = 0, sxx = 0, sxy = 0;
                for (int i = 0; i < n; i++) {
                    sx += i; sy += history[i]; sxx += (double) i * i; sxy += i * history[i];
                }
                double denom = n * sxx - sx * sx;
                double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
                double intercept = (sy - slope * sx) / n;
                double[] f = new double[n];
                for (int i = 0; i < n; i++) f[i] = intercept + slope * i;
                yield f;
            }
            case HOLT_DOUBLE_EXPONENTIAL -> {
                double alpha = 0.5, beta = 0.1;
                double level = history[0];
                double trend = history.length > 1 ? history[1] - history[0] : 0;
                double[] f = new double[history.length];
                f[0] = history[0];
                for (int i = 1; i < history.length; i++) {
                    f[i] = level + trend;
                    double prevLevel = level;
                    level = alpha * history[i] + (1 - alpha) * (level + trend);
                    trend = beta * (level - prevLevel) + (1 - beta) * trend;
                }
                yield f;
            }
        };
        double sumSq = 0;
        int count = 0;
        for (int i = 1; i < history.length; i++) {
            double r = history[i] - fitted[i];
            sumSq += r * r;
            count++;
        }
        return count == 0 ? 0.0 : Math.sqrt(sumSq / count);
    }
}
