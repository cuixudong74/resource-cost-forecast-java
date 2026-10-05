package com.github.cuixudong74.resourcecostforecastjava.budget;

import com.github.cuixudong74.resourcecostforecastjava.domain.Alert;
import com.github.cuixudong74.resourcecostforecastjava.domain.Budget;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预算与告警。按团队/资源维度配置预算与阈值；告警按
 * (budgetId, threshold, 账期) 去重：不重复、不漏报，且带触发依据。
 */
@Service
public class BudgetService {

    private final ConcurrentHashMap<String, Budget> budgets = new ConcurrentHashMap<>();
    /** alertKey -> Alert，putIfAbsent 保证同一键只告警一次。 */
    private final ConcurrentHashMap<String, Alert> alerts = new ConcurrentHashMap<>();

    public Budget register(Budget budget) {
        if (budget.limitAmount() == null || budget.limitAmount().signum() <= 0) {
            throw new IllegalArgumentException("limitAmount must be positive");
        }
        for (double t : budget.thresholds()) {
            if (t <= 0) {
                throw new IllegalArgumentException("thresholds must be positive");
            }
        }
        budgets.put(budget.budgetId(), budget);
        return budget;
    }

    public Optional<Budget> budget(String budgetId) {
        return Optional.ofNullable(budgets.get(budgetId));
    }

    public List<Budget> budgets() {
        return List.copyOf(budgets.values());
    }

    /**
     * 依据一次估算评估预算，返回本次新产生的告警（已去重）。
     * 匹配规则：团队相同，且预算 resourceId 为 null（团队级）或与估算资源相同。
     */
    public List<Alert> evaluate(CostEstimate estimate) {
        List<Alert> raised = new ArrayList<>();
        for (Budget budget : budgets.values()) {
            if (!budget.teamId().equals(estimate.teamId())) {
                continue;
            }
            if (budget.resourceId() != null && !budget.resourceId().equals(estimate.resourceId())) {
                continue;
            }
            for (double threshold : budget.thresholds()) {
                BigDecimal thresholdAmount = budget.limitAmount()
                        .multiply(BigDecimal.valueOf(threshold));
                if (estimate.amount().compareTo(thresholdAmount) < 0) {
                    continue; // 未跨越该阈值
                }
                // 去重键：同一预算 + 阈值 + 账期 + 资源只告警一次。
                String alertKey = budget.budgetId() + "|" + threshold + "|"
                        + estimate.resourceId() + "|" + estimate.periodStart();
                String basis = "estimate " + estimate.estimateId()
                        + " amount=" + estimate.amount()
                        + " crossed " + (threshold * 100) + "% of limit " + budget.limitAmount()
                        + " (thresholdAmount=" + thresholdAmount
                        + ", ruleVersion=" + estimate.ruleVersion() + ")";
                Alert alert = new Alert(alertKey, budget.budgetId(), estimate.teamId(),
                        estimate.resourceId(), threshold, estimate.amount(),
                        budget.limitAmount(), estimate.ruleVersion(), basis, Instant.now());
                // putIfAbsent：并发/重复评估都不会产生重复告警。
                if (alerts.putIfAbsent(alertKey, alert) == null) {
                    raised.add(alert);
                }
            }
        }
        raised.sort(Comparator.comparing(Alert::alertKey));
        return raised;
    }

    public List<Alert> alerts() {
        return alerts.values().stream()
                .sorted(Comparator.comparing(Alert::raisedAt).thenComparing(Alert::alertKey))
                .toList();
    }
}
