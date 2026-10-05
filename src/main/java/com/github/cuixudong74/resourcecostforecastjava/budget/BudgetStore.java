package com.github.cuixudong74.resourcecostforecastjava.budget;

import com.github.cuixudong74.resourcecostforecastjava.domain.Budget;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 预算配置仓库（团队 + 资源维度，resourceId 为 null 表示团队级预算）。 */
@Component
public class BudgetStore {

    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();

    public void upsert(Budget budget) {
        budgets.put(budget.budgetId(), budget);
    }

    /** 匹配某团队/资源/周期的预算：团队级（resourceId=null）与资源级都命中。 */
    public List<Budget> budgetsFor(String teamId, String resourceId, long periodEpoch) {
        return budgets.values().stream()
                .filter(b -> b.teamId().equals(teamId))
                .filter(b -> b.periodEpoch() == periodEpoch)
                .filter(b -> b.resourceId() == null || b.resourceId().equals(resourceId))
                .toList();
    }

    public List<Budget> all() {
        return List.copyOf(budgets.values());
    }
}
