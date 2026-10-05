package com.github.cuixudong74.resourcecostforecastjava.tests;

import com.github.cuixudong74.resourcecostforecastjava.budget.BudgetService;
import com.github.cuixudong74.resourcecostforecastjava.domain.Alert;
import com.github.cuixudong74.resourcecostforecastjava.domain.Budget;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 告警去重：跨阈值产生告警，不重复、不漏报，且带触发依据。 */
class AlertDedupTest {

    private final BudgetService budgetService = new BudgetService();

    private CostEstimate estimate(String id, String teamId, String resourceId,
                                  String amount, Instant periodStart) {
        return new CostEstimate(id, teamId, resourceId, 1L, periodStart,
                periodStart.plus(1, ChronoUnit.HOURS), 1.0, new BigDecimal(amount),
                List.of(), List.of(), Instant.now());
    }

    @Test
    void crossingThresholdsRaisesEachAlertExactlyOnce() {
        Instant period = Instant.parse("2026-06-01T00:00:00Z");
        budgetService.register(new Budget("b1", "team-a", "cpu",
                new BigDecimal("100"), List.of(0.8, 1.0)));

        // 未跨阈值：无告警
        assertTrue(budgetService.evaluate(estimate("e1", "team-a", "cpu", "50", period)).isEmpty());

        // 跨过 80%：恰好一条告警，含触发依据
        List<Alert> first = budgetService.evaluate(estimate("e2", "team-a", "cpu", "85", period));
        assertEquals(1, first.size());
        assertEquals(0.8, first.get(0).threshold());
        assertTrue(first.get(0).basis().contains("85"));
        assertTrue(first.get(0).basis().contains("100"));

        // 重复评估同一账期同一阈值：不重复告警
        assertTrue(budgetService.evaluate(estimate("e3", "team-a", "cpu", "86", period)).isEmpty());

        // 跨过 100%：只新增 100% 这条，80% 不重复
        List<Alert> second = budgetService.evaluate(estimate("e4", "team-a", "cpu", "120", period));
        assertEquals(1, second.size());
        assertEquals(1.0, second.get(0).threshold());

        // 新账期重新评估：阈值告警重新生效（不漏报）
        Instant nextPeriod = period.plus(1, ChronoUnit.DAYS);
        List<Alert> third = budgetService.evaluate(estimate("e5", "team-a", "cpu", "130", nextPeriod));
        assertEquals(2, third.size());

        // 总量核对：2(首账期) + 2(次账期) = 4
        assertEquals(4, budgetService.alerts().size());
    }

    @Test
    void teamAndResourceScoping() {
        Instant period = Instant.parse("2026-06-01T00:00:00Z");
        budgetService.register(new Budget("team-wide", "team-a", null,
                new BigDecimal("100"), List.of(1.0)));
        budgetService.register(new Budget("gpu-only", "team-a", "gpu",
                new BigDecimal("10"), List.of(1.0)));
        budgetService.register(new Budget("other-team", "team-b", null,
                new BigDecimal("100"), List.of(1.0)));

        // cpu 估算只命中 team-wide，不命中 gpu-only / other-team
        List<Alert> alerts = budgetService.evaluate(estimate("e1", "team-a", "cpu", "150", period));
        assertEquals(1, alerts.size());
        assertEquals("team-wide", alerts.get(0).budgetId());
    }

    @Test
    void concurrentEvaluationDoesNotDuplicateAlerts() throws Exception {
        Instant period = Instant.parse("2026-06-01T00:00:00Z");
        budgetService.register(new Budget("b1", "team-a", "cpu",
                new BigDecimal("100"), List.of(0.5, 0.8, 1.0)));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger totalRaised = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    go.await();
                    totalRaised.addAndGet(budgetService.evaluate(
                            estimate("e-" + idx, "team-a", "cpu", "120", period)).size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        // 16 个线程并发评估同一账期：3 个阈值各告警恰好一次
        assertEquals(3, totalRaised.get());
        assertEquals(3, budgetService.alerts().size());
    }
}
