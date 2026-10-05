package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.budget.AlertService;
import com.github.cuixudong74.resourcecostforecastjava.budget.BudgetStore;
import com.github.cuixudong74.resourcecostforecastjava.domain.Alert;
import com.github.cuixudong74.resourcecostforecastjava.domain.Budget;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class BudgetController {

    private final BudgetStore budgetStore;
    private final AlertService alertService;

    public BudgetController(BudgetStore budgetStore, AlertService alertService) {
        this.budgetStore = budgetStore;
        this.alertService = alertService;
    }

    @PostMapping("/budgets")
    public void upsert(@RequestBody Budget budget) {
        budgetStore.upsert(budget);
    }

    @GetMapping("/budgets")
    public List<Budget> budgets() {
        return budgetStore.all();
    }

    @PostMapping("/alerts/evaluate")
    public List<Alert> evaluate(@RequestParam String teamId,
                                @RequestParam(required = false) String resourceId,
                                @RequestParam long period,
                                @RequestParam BigDecimal actual) {
        return alertService.evaluate(teamId, resourceId, period, actual);
    }

    @GetMapping("/alerts")
    public List<Alert> alerts() {
        return alertService.all();
    }
}
