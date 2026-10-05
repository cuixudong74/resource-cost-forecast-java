package com.github.cuixudong74.resourcecostforecastjava;

import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发读写：任一时刻读到的规则必须是某个已提交的完整版本，不得字段级混合。 */
class RuleConcurrencyTest {

    @Test
    void readersAlwaysSeeCompleteConsistentVersion() throws InterruptedException {
        RuleStore store = new RuleStore();
        int publishers = 4;
        int readers = 8;
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(publishers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistencies = new AtomicInteger();

        // 每个发布者写入 version 与 unitPrice 强关联的规则：
        // 若出现字段级混合，读者会看到 version 与价格不匹配。
        for (int p = 0; p < publishers; p++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        long marker = System.nanoTime();
                        store.publish(new BillingRule(0, Instant.now(),
                                Map.of("cpu", BigDecimal.valueOf(marker)),
                                BigDecimal.ZERO, 2, RoundingMode.HALF_UP,
                                "marker-" + marker));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds * publishers; i++) {
                        BillingRule rule = store.current();
                        // 完整性校验 1：当前版本必然已登记在历史中
                        if (store.get(rule.version()).isEmpty()) {
                            inconsistencies.incrementAndGet();
                            continue;
                        }
                        // 完整性校验 2：description 的 marker 与价格 marker 必须一致
                        String desc = rule.description();
                        if (desc.startsWith("marker-")) {
                            long marker = Long.parseLong(desc.substring("marker-".length()));
                            if (rule.unitPriceOf("cpu").longValue() != marker) {
                                inconsistencies.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(0, inconsistencies.get(), "读者看到了字段级混合的规则版本");
        // 版本号连续递增：初始 v1 + publishers*rounds 次发布
        assertEquals(1 + publishers * rounds, store.current().version());
    }
}
