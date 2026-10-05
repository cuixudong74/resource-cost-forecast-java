package com.github.cuixudong74.resourcecostforecastjava.tests;

import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import com.github.cuixudong74.resourcecostforecastjava.estimation.CostEstimator;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
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

/** 并发读写：估算与规则发布并发进行，读取永远是某个完整已提交版本。 */
class ConcurrencyTest {

    @Test
    void readersAlwaysSeeCompleteCommittedVersion() throws Exception {
        RuleStore ruleStore = new RuleStore();
        UsageStore usageStore = new UsageStore();
        EstimationService estimationService = new EstimationService(
                usageStore, ruleStore, new CostEstimator(), new AppProperties());

        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = start.plus(1, ChronoUnit.HOURS);
        usageStore.ingest(new UsageRecord("r1", "team-a", "cpu", start, end,
                4.0, "core-hour", start.plusSeconds(1), UsageStatus.ON_TIME));

        int versions = 50;
        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger inconsistent = new AtomicInteger();

        // 写线程：连续发布 50 个版本，cpu 单价 = 版本号，mem 单价 = 版本号 * 10
        pool.submit(() -> {
            try {
                go.await();
                for (int v = 1; v <= versions; v++) {
                    ruleStore.publish(List.of(
                            new PricingRule("cpu", "core-hour",
                                    BigDecimal.valueOf(v), BigDecimal.ZERO),
                            new PricingRule("mem", "gb-hour",
                                    BigDecimal.valueOf(v * 10L), BigDecimal.ZERO)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 读线程：并发读取 + 估算，校验快照内部一致性（cpu*10 == mem）
        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    for (int iter = 0; iter < 500; iter++) {
                        RuleSet snapshot = ruleStore.current();
                        if (snapshot.version() >= 1) {
                            BigDecimal cpu = snapshot.ruleFor("cpu").unitPrice();
                            BigDecimal mem = snapshot.ruleFor("mem").unitPrice();
                            // 字段级混合检测：同一快照内两条规则必须来自同一版本
                            if (mem.compareTo(cpu.multiply(BigDecimal.TEN)) != 0) {
                                inconsistent.incrementAndGet();
                            }
                        }
                        CostEstimate estimate = estimationService.estimate(
                                "team-a", "cpu", start, end, null);
                        // 估算归属版本必须可追溯且确实存在
                        assertTrue(ruleStore.version(estimate.ruleVersion()).isPresent());
                        RuleSet used = ruleStore.version(estimate.ruleVersion()).orElseThrow();
                        assertEquals(estimate.ruleVersion(), used.version());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(0, inconsistent.get(), "readers observed field-level mixed rule sets");
        assertEquals(versions + 1L, ruleStore.versions().size()); // 含初始 v0
    }
}
