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

import static org.junit.jupiter.api.Assertions.*;

/** 重启恢复：从最近已提交位置继续；已提交与未完成批次可区分；不重复计费。 */
class RecoveryTest {

    @TempDir
    Path checkpointDir;

    private static final Instant START = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant END = START.plus(1, ChronoUnit.HOURS);

    private BatchProcessor newProcessor(UsageStore usageStore, RuleStore ruleStore,
                                        EstimationService estimationService) {
        AppProperties props = new AppProperties();
        props.setCheckpointDir(checkpointDir.toString());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new BatchProcessor(usageStore, ruleStore, new CostEstimator(),
                estimationService, new CheckpointStore(mapper, props), props);
    }

    @Test
    void committedBatchesSurviveRestartAndAreNotRebilled() {
        RuleStore ruleStore = new RuleStore();
        ruleStore.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("2.00"), BigDecimal.ZERO)));
        UsageStore usageStore = new UsageStore();
        usageStore.ingest(new UsageRecord("r1", "team-a", "cpu", START, END,
                5.0, "core-hour", START.plusSeconds(1), UsageStatus.ON_TIME));
        EstimationService estimationService = new EstimationService(
                usageStore, ruleStore, new CostEstimator(), new AppProperties());

        BatchJob job = new BatchJob("job-1", "team-a", "cpu", START, END, null);
        BatchProcessor first = newProcessor(usageStore, ruleStore, estimationService);
        BatchOutcome committed = first.submit(job, Duration.ofSeconds(10));
        assertEquals(BatchStatus.COMMITTED, committed.status());
        assertEquals(new BigDecimal("10.000000"), committed.estimate().amount());
        first.shutdown();

        // 模拟重启：全新实例，从检查点恢复
        BatchProcessor second = newProcessor(usageStore, ruleStore, estimationService);
        BatchOutcome recovered = second.outcome("job-1").orElseThrow();
        assertEquals(BatchStatus.COMMITTED, recovered.status());
        assertEquals(new BigDecimal("10.000000"), recovered.estimate().amount());

        // 重复触发同一 jobId：收敛到同一已提交结果，不重复计费
        BatchOutcome resubmitted = second.submit(job, Duration.ofSeconds(10));
        assertEquals(BatchStatus.COMMITTED, resubmitted.status());
        assertEquals(committed.estimate().estimateId(), resubmitted.estimate().estimateId());
        // 估算服务中只有一份该估算
        assertEquals(1, estimationService.estimate("est-job-1").stream().toList().size());
        second.shutdown();
    }

    @Test
    void unfinishedBatchesAreInvisibleAfterRestart() {
        RuleStore ruleStore = new RuleStore();
        ruleStore.publish(List.of(
                new PricingRule("cpu", "core-hour", new BigDecimal("2.00"), BigDecimal.ZERO)));
        UsageStore usageStore = new UsageStore();
        usageStore.ingest(new UsageRecord("r1", "team-a", "cpu", START, END,
                5.0, "core-hour", START.plusSeconds(1), UsageStatus.ON_TIME));
        EstimationService estimationService = new EstimationService(
                usageStore, ruleStore, new CostEstimator(), new AppProperties());

        BatchProcessor first = newProcessor(usageStore, ruleStore, estimationService);
        first.setComputeDelayMillis(2000);
        BatchJob slowJob = new BatchJob("job-slow", "team-a", "cpu", START, END, null);
        BatchOutcome timedOut = first.submit(slowJob, Duration.ofMillis(100));
        assertEquals(BatchStatus.TIMED_OUT, timedOut.status());
        assertNull(timedOut.estimate());
        first.shutdown();

        // 重启：超时的未完成批次不可见（未提交），可安全重跑
        BatchProcessor second = newProcessor(usageStore, ruleStore, estimationService);
        assertTrue(second.outcome("job-slow").isEmpty());
        BatchOutcome rerun = second.submit(slowJob, Duration.ofSeconds(10));
        assertEquals(BatchStatus.COMMITTED, rerun.status());
        assertEquals(new BigDecimal("10.000000"), rerun.estimate().amount());
        second.shutdown();
    }
}
