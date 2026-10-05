package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.budget.BudgetService;
import com.github.cuixudong74.resourcecostforecastjava.domain.Alert;
import com.github.cuixudong74.resourcecostforecastjava.domain.Budget;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @PostMapping
    public Budget register(@RequestBody Budget budget) {
        return budgetService.register(budget);
    }

    @GetMapping
    public List<Budget> budgets() {
        return budgetService.budgets();
    }

    @GetMapping("/alerts")
    public List<Alert> alerts() {
        return budgetService.alerts();
    }
}
