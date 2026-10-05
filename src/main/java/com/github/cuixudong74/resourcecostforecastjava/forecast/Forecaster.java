package com.github.cuixudong74.resourcecostforecastjava.forecast;

import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastConfig;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastPoint;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastResult;
import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 成本预测：按可配置窗口与策略外推。相同输入 + 相同种子 => 结果逐次复现。
 * 缺失账期不参与拟合（不补零）。
 */
@Component
public class Forecaster {

    public ForecastResult forecast(String teamId, String resourceId,
                                   List<UsageRecord> history,
                                   ForecastConfig config, RuleSet ruleSet,
                                   Instant firstFuturePeriodStart, long periodMillis) {
        if (config.window() <= 0 || config.horizon() <= 0) {
            throw new IllegalArgumentException("window and horizon must be positive");
        }
        PricingRule rule = ruleSet.ruleFor(resourceId);
        if (rule == null) {
            throw new IllegalStateException("no pricing rule for resource " + resourceId
                    + " in rule version " + ruleSet.version());
        }

        // 只使用真实到达的用量；MISSING 占位不参与拟合（不补零）。
        List<Double> series = history.stream()
                .filter(r -> r.status() != UsageStatus.MISSING)
                .sorted(java.util.Comparator.comparing(UsageRecord::periodStart))
                .map(UsageRecord::quantity)
                .toList();
        if (series.isEmpty()) {
            throw new IllegalStateException("no usable usage history for " + teamId + "/" + resourceId);
        }

        int from = Math.max(0, series.size() - config.window());
        List<Double> window = series.subList(from, series.size());

        // 残差标准差用于种子扰动，保证种子对结果有确定性的影响。
        double stddev = stddev(window);
        // 每次预测都从同一种子新建 Random，保证可复现。
        Random random = new Random(config.seed());

        List<ForecastPoint> points = new ArrayList<>();
        double freeQuota = rule.freeQuota() == null ? 0.0d : rule.freeQuota().doubleValue();
        for (int step = 1; step <= config.horizon(); step++) {
            double base = switch (config.strategy()) {
                case MOVING_AVERAGE -> movingAverage(window);
                case LINEAR_TREND -> linearTrendExtrapolate(window, step);
            };
            // 确定性扰动：±5% 内的均匀抖动，幅度随历史波动缩放。
            double jitter = (random.nextDouble() - 0.5d) * 0.1d * (stddev + Math.abs(base) * 0.01d);
            double expected = Math.max(0.0d, base + jitter);
            double billable = Math.max(0.0d, expected - freeQuota);
            BigDecimal cost = rule.unitPrice()
                    .multiply(BigDecimal.valueOf(billable))
                    .setScale(6, RoundingMode.HALF_UP);
            points.add(new ForecastPoint(
                    firstFuturePeriodStart.plusMillis(periodMillis * (step - 1)),
                    expected, cost));
        }
        return new ForecastResult(teamId, resourceId, config.strategy(), config.seed(),
                ruleSet.version(), points);
    }

    private double movingAverage(List<Double> window) {
        return window.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    }

    /** 对窗口做最小二乘拟合，外推第 step 个未来点。 */
    private double linearTrendExtrapolate(List<Double> window, int step) {
        int n = window.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += window.get(i);
            sumXY += i * window.get(i);
            sumXX += (double) i * i;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0.0d) {
            return sumY / n; // 退化为均值
        }
        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        return intercept + slope * (n - 1 + step);
    }

    private double stddev(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0.0d);
        return Math.sqrt(var);
    }
}
