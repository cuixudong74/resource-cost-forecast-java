package com.github.cuixudong74.resourcecostforecastjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.cuixudong74.resourcecostforecastjava.batch.BatchJob;
import com.github.cuixudong74.resourcecostforecastjava.batch.BatchOutcome;
import com.github.cuixudong74.resourcecostforecastjava.batch.BatchProcessor;
import com.github.cuixudong74.resourcecostforecastjava.batch.BatchStatus;
import com.github.cuixudong74.resourcecostforecastjava.batch.CheckpointStore;
import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import com.github.cuixudong74.resourcecostforecastjava.estimation.CostEstimator;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 取消与超时：不留部分写入或中间状态，重复触发安全收敛。 */
class CancellationTimeoutTest {

    @TempDir
    Path checkpointDir;

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant END = START.plus(1, ChronoUnit.HOURS);

    private BatchProcessor newProcessor(EstimationService estimationService,
                                        UsageStore usageStore, RuleStore ruleStore) {
        AppProperties props = new AppProperties();
        props.setCheckpointDir(checkpointDir.toString());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new BatchProcessor(usageStore, ruleStore, new CostEstimator(),
                estimationService, new CheckpointStore(mapper, props), props);
    }

    private RuleStore ruleStore() {
        RuleStore store = new RuleStore();
        store.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("2.00"), BigDecimal.ZERO)));
        return store;
    }

    private UsageStore usageStore() {
        UsageStore store = new UsageStore();
        store.ingest(new UsageRecord("r1", "team-a", "cpu", START, END,
                5.0, "core-hour", START.plusSeconds(1), UsageStatus.ON_TIME));
        return store;
    }

    @Test
    void timeoutLeavesNoPartialWritesAndRetryConverges() {
        RuleStore rules = ruleStore();
        UsageStore usage = usageStore();
        EstimationService estimationService = new EstimationService(
                usage, rules, new CostEstimator(), new AppProperties());
        BatchProcessor processor = newProcessor(estimationService, usage, rules);
        processor.setComputeDelayMillis(2000);

        BatchJob job = new BatchJob("job-t", "team-a", "cpu", START, END, null);
        BatchOutcome timedOut = processor.submit(job, Duration.ofMillis(100));
        assertEquals(BatchStatus.TIMED_OUT, timedOut.status());
        assertNull(timedOut.estimate());
        // 无部分写入：磁盘日志为空，估算服务无该估算
        assertTrue(new CheckpointStore(new ObjectMapper().registerModule(new JavaTimeModule()),
                propsOf()).loadCommitted().isEmpty());
        assertTrue(estimationService.estimate("est-job-t").isEmpty());

        // 重试（不再延迟）：安全收敛到已提交结果
        processor.setComputeDelayMillis(0);
        BatchOutcome retried = processor.submit(job, Duration.ofSeconds(10));
        assertEquals(BatchStatus.COMMITTED, retried.status());
        assertEquals(new BigDecimal("10.000000"), retried.estimate().amount());

        // 再次重复触发：返回同一已提交结果
        BatchOutcome again = processor.submit(job, Duration.ofSeconds(10));
        assertEquals(retried.estimate().estimateId(), again.estimate().estimateId());
        processor.shutdown();
    }

    private AppProperties propsOf() {
        AppProperties props = new AppProperties();
        props.setCheckpointDir(checkpointDir.toString());
        return props;
    }

    @Test
    void cancelInFlightBatchLeavesNoCommittedState() throws Exception {
        RuleStore rules = ruleStore();
        UsageStore usage = usageStore();
        EstimationService estimationService = new EstimationService(
                usage, rules, new CostEstimator(), new AppProperties());
        BatchProcessor processor = newProcessor(estimationService, usage, rules);
        processor.setComputeDelayMillis(3000);

        BatchJob job = new BatchJob("job-c", "team-a", "cpu", START, END, null);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<BatchOutcome> pending = caller.submit(
                () -> processor.submit(job, Duration.ofSeconds(30)));
        // 等批次进入 RUNNING 再取消
        Thread.sleep(200);
        assertTrue(processor.cancel("job-c"));

        BatchOutcome outcome = pending.get(10, TimeUnit.SECONDS);
        assertEquals(BatchStatus.CANCELLED, outcome.status());
        assertNull(outcome.estimate());
        // 取消后无提交记录
        assertTrue(new CheckpointStore(new ObjectMapper().registerModule(new JavaTimeModule()),
                propsOf()).loadCommitted().isEmpty());

        // 取消后重跑可正常提交
        processor.setComputeDelayMillis(0);
        BatchOutcome rerun = processor.submit(job, Duration.ofSeconds(10));
        assertEquals(BatchStatus.COMMITTED, rerun.status());
        // 已提交的批次不可取消
        assertFalse(processor.cancel("job-c"));

        caller.shutdown();
        processor.shutdown();
    }
}
