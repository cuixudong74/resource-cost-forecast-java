package com.github.cuixudong74.resourcecostforecastjava;

import com.github.cuixudong74.resourcecostforecastjava.budget.AlertService;
import com.github.cuixudong74.resourcecostforecastjava.budget.BudgetStore;
import com.github.cuixudong74.resourcecostforecastjava.domain.Alert;
import com.github.cuixudong74.resourcecostforecastjava.domain.Budget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 告警：跨阈值触发、不漏不重、并发去重、携带触发依据。 */
class AlertDedupTest {

    private AlertService serviceWithBudget() {
        BudgetStore budgets = new BudgetStore();
        budgets.upsert(new Budget("b-team", "team-a", null, 1L,
                new BigDecimal("1000.00"), List.of(0.8, 1.0)));
        budgets.upsert(new Budget("b-res", "team-a", "res-1", 1L,
                new BigDecimal("100.00"), List.of(1.0)));
        return new AlertService(budgets);
    }

    @Test
    void crossingThresholdFiresOnceWithReason() {
        AlertService alerts = serviceWithBudget();
        // 未跨阈值：不告警（不漏报的前提是低于阈值不报）
        assertTrue(alerts.evaluate("team-a", "res-1", 1L, new BigDecimal("50")).isEmpty());
        // 跨过团队级 80% 与资源级 100%：触发两条
        List<Alert> first = alerts.evaluate("team-a", "res-1", 1L, new BigDecimal("850"));
        assertEquals(2, first.size());
        Alert alert = first.stream().filter(a -> a.threshold() == 0.8).findFirst().orElseThrow();
        assertTrue(alert.reason().contains("850"), "告警必须携带触发依据");
        // 重复评估：不重复告警
        assertTrue(alerts.evaluate("team-a", "res-1", 1L, new BigDecimal("850")).isEmpty());
        // 跨过团队级 100%：只触发新阈值
        List<Alert> second = alerts.evaluate("team-a", "res-1", 1L, new BigDecimal("1000"));
        assertEquals(1, second.size());
        // 再评估：无新告警
        assertTrue(alerts.evaluate("team-a", "res-1", 1L, new BigDecimal("1000")).isEmpty());
        assertEquals(3, alerts.all().size());
    }

    @Test
    void concurrentEvaluationFiresExactlyOnce() throws InterruptedException {
        AlertService alerts = serviceWithBudget();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalFired = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 所有线程同时评估同一超支成本
                    totalFired.addAndGet(alerts.evaluate(
                            "team-a", "res-1", 1L, new BigDecimal("1500")).size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        // 团队级 0.8、团队级 1.0、资源级 1.0 各一条，共 3 条，不多不少
        assertEquals(3, totalFired.get());
        assertEquals(3, alerts.all().size());
    }
}
