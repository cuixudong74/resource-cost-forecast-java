package com.github.cuixudong74.resourcecostforecastjava.budget;

import com.github.cuixudong74.resourcecostforecastjava.domain.Alert;
import com.github.cuixudong74.resourcecostforecastjava.domain.Budget;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警服务。
 *
 * 去重语义：alertKey = budgetId|period|threshold，通过
 * ConcurrentHashMap.putIfAbsent 原子占位——同一预算同一周期同一阈值
 * 无论评估多少次、多少线程并发，都只产生一条告警（不漏不重）。
 * 每条告警携带触发依据（实际值、阈值比例、预算额）。
 */
@Service
public class AlertService {

    private final BudgetStore budgetStore;
    private final Map<String, Alert> fired = new ConcurrentHashMap<>();

    public AlertService(BudgetStore budgetStore) {
        this.budgetStore = budgetStore;
    }

    /**
     * 评估实际成本是否跨越阈值，返回本次新触发的告警（已去重）。
     * 重复评估返回空列表。
     */
    public List<Alert> evaluate(String teamId, String resourceId, long periodEpoch, BigDecimal actual) {
        List<Alert> newAlerts = new ArrayList<>();
        for (Budget budget : budgetStore.budgetsFor(teamId, resourceId, periodEpoch)) {
            for (double threshold : budget.thresholds()) {
                BigDecimal limit = budget.amount().multiply(BigDecimal.valueOf(threshold));
                if (actual.compareTo(limit) < 0) {
                    continue; // 未跨越该阈值
                }
                String key = budget.budgetId() + "|" + periodEpoch + "|" + threshold;
                String reason = String.format(
                        "actual=%s crossed %.0f%% of budget=%s (limit=%s) for team=%s resource=%s period=%d",
                        actual.toPlainString(), threshold * 100, budget.amount().toPlainString(),
                        limit.toPlainString(), teamId,
                        resourceId == null ? "*" : resourceId, periodEpoch);
                Alert alert = new Alert(key, budget.budgetId(), teamId, resourceId, periodEpoch,
                        threshold, actual, budget.amount(), reason, Instant.now());
                if (fired.putIfAbsent(key, alert) == null) {
                    newAlerts.add(alert); // 只有第一个占位成功的线程产生告警
                }
            }
        }
        return newAlerts;
    }

    public List<Alert> all() {
        return List.copyOf(fired.values());
    }
}
