package com.github.cuixudong74.resourcecostforecastjava;

import com.github.cuixudong74.resourcecostforecastjava.batch.BatchRunner;
import com.github.cuixudong74.resourcecostforecastjava.batch.JournalStore;
import com.github.cuixudong74.resourcecostforecastjava.domain.BatchJob;
import com.github.cuixudong74.resourcecostforecastjava.domain.BatchStatus;
import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.estimation.UsageStore;
import com.github.cuixudong74.resourcecostforecastjava.rules.BillingEngine;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 重启恢复、取消与超时：从最近提交位置继续，不重复计费、不丢已提交结果、无部分写入。 */
class BatchRecoveryTest {

    @TempDir
    Path journalDir;

    private RuleStore rules() {
        RuleStore rules = new RuleStore();
        rules.publish(new BillingRule(0, Instant.now(), Map.of("cpu", new BigDecimal("0.10")),
                BigDecimal.ZERO, 2, RoundingMode.HALF_UP, "v2"));
        return rules;
    }

    private UsageStore usage(int periods) {
        UsageStore store = new UsageStore();
        LongStream.rangeClosed(1, periods).forEach(p ->
                store.ingest(new UsagePoint("t", "r", "cpu", p, 10.0,
                        Instant.now(), DataQuality.ON_TIME)));
        return store;
    }

    private static List<Long> periods(int n) {
        return LongStream.rangeClosed(1, n).boxed().toList();
    }

    private long commitLines(String batchId) throws Exception {
        Path file = journalDir.resolve("journal.jsonl");
        if (!Files.exists(file)) {
            return 0;
        }
        return Files.readAllLines(file).stream()
                .filter(l -> l.contains("\"COMMIT\"") && l.contains(batchId))
                .count();
    }

    @Test
    void timeoutLeavesOnlyCommittedResultsAndNoPartialWrites() throws Exception {
        RuleStore rules = rules();
        EstimationService estimation = new EstimationService(rules, new BillingEngine(), usage(5));
        JournalStore journal = new JournalStore(journalDir.toString());
        BatchRunner runner = new BatchRunner(estimation, journal, rules);

        runner.submit("b-timeout", "t", "r", periods(5), Duration.ofNanos(1));
        BatchJob job = runner.await("b-timeout", Duration.ofSeconds(10));
        assertEquals(BatchStatus.TIMED_OUT, job.status());
        assertEquals(0, job.committedCount());
        assertEquals(0, commitLines("b-timeout"), "超时不得留下任何部分写入");
        runner.shutdown();
    }

    @Test
    void restartResumesFromLastCommittedPositionWithoutDoubleBilling() throws Exception {
        int total = 2000;
        // 第一次运行：短超时，只提交一部分后“崩溃”（不再触碰该实例）
        RuleStore rules1 = rules();
        EstimationService est1 = new EstimationService(rules1, new BillingEngine(), usage(total));
        JournalStore journal1 = new JournalStore(journalDir.toString());
        BatchRunner runner1 = new BatchRunner(est1, journal1, rules1);
        runner1.submit("b1", "t", "r", periods(total), Duration.ofMillis(5));
        BatchJob crashed = runner1.await("b1", Duration.ofSeconds(30));
        assertEquals(BatchStatus.TIMED_OUT, crashed.status());
        int committedBeforeCrash = crashed.committedCount();
        long linesBeforeCrash = commitLines("b1");
        assertEquals(committedBeforeCrash, linesBeforeCrash);
        runner1.shutdown();

        // 重启：全新实例，同一日志目录
        RuleStore rules2 = rules();
        EstimationService est2 = new EstimationService(rules2, new BillingEngine(), usage(total));
        JournalStore journal2 = new JournalStore(journalDir.toString());
        assertEquals(committedBeforeCrash, journal2.committedCount("b1"),
                "重启后必须能区分已提交位置");
        BatchRunner runner2 = new BatchRunner(est2, journal2, rules2);
        runner2.resume("b1");
        BatchJob done = runner2.await("b1", Duration.ofSeconds(60));
        assertEquals(BatchStatus.COMMITTED, done.status());
        assertEquals(total, done.committedCount());

        // 不重复计费：每个周期恰好一条 COMMIT
        assertEquals(total, commitLines("b1"));
        // 已提交结果不丢失
        assertEquals(total, journal2.committedEstimates("b1").size());

        // 重复触发安全收敛：再次 resume 不产生新写入
        runner2.resume("b1");
        runner2.await("b1", Duration.ofSeconds(10));
        assertEquals(total, commitLines("b1"));
        runner2.shutdown();
    }

    @Test
    void cancelConvergesAndLeavesOnlyCommittedResults() throws Exception {
        int total = 50000;
        RuleStore rules = rules();
        EstimationService estimation = new EstimationService(rules, new BillingEngine(), usage(total));
        JournalStore journal = new JournalStore(journalDir.toString());
        BatchRunner runner = new BatchRunner(estimation, journal, rules);

        runner.submit("b-cancel", "t", "r", periods(total), null);
        runner.cancel("b-cancel");
        BatchJob job = runner.await("b-cancel", Duration.ofSeconds(60));
        assertTrue(job.status() == BatchStatus.CANCELLED || job.status() == BatchStatus.COMMITTED);
        // 已提交计数与日志一致：无部分写入
        assertEquals(job.committedCount(), commitLines("b-cancel"));

        // 重复提交同 batchId 收敛到同一任务
        BatchJob again = runner.submit("b-cancel", "t", "r", periods(total), null);
        assertEquals(job.batchId(), again.batchId());

        // 恢复后完成全部周期，且总行数精确等于周期数（不重复计费）
        runner.resume("b-cancel");
        BatchJob done = runner.await("b-cancel", Duration.ofSeconds(120));
        assertEquals(BatchStatus.COMMITTED, done.status());
        assertEquals(total, commitLines("b-cancel"));
        runner.shutdown();
    }
}
