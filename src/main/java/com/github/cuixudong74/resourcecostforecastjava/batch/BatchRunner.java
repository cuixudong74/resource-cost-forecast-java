package com.github.cuixudong74.resourcecostforecastjava.batch;

import com.github.cuixudong74.resourcecostforecastjava.domain.BatchJob;
import com.github.cuixudong74.resourcecostforecastjava.domain.BatchStatus;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 批量估算执行器。
 *
 * - 逐周期估算，每完成一个周期就向日志提交一次（检查点）；
 * - 取消/超时：循环每次迭代检查取消标志与截止时间，停止时只保留
 *   已提交的结果，未提交部分不产生任何写入（无部分写入）；
 * - 恢复：重启后 resume 从日志中最近已提交位置继续；
 * - 幂等：重复提交同 batchId 直接返回既有任务；估算结果按确定性
 *   estimateId 去重，重复触发收敛到同一结果。
 */
@Service
public class BatchRunner {

    private final EstimationService estimationService;
    private final JournalStore journal;
    private final RuleStore ruleStore;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, BatchJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public BatchRunner(EstimationService estimationService, JournalStore journal, RuleStore ruleStore) {
        this.estimationService = estimationService;
        this.journal = journal;
        this.ruleStore = ruleStore;
    }

    /** 提交批次（异步执行）。同 batchId 重复提交返回既有任务（幂等）。 */
    public BatchJob submit(String batchId, String teamId, String resourceId,
                           List<Long> periods, Duration timeout) {
        BatchJob existing = jobs.get(batchId);
        if (existing != null) {
            return existing;
        }
        long ruleVersion = ruleStore.current().version();
        journal.recordStart(batchId, teamId, resourceId, periods, ruleVersion);
        BatchJob job = new BatchJob(batchId, periods, journal.committedCount(batchId),
                BatchStatus.RUNNING, ruleVersion, Instant.now(), Instant.now());
        jobs.putIfAbsent(batchId, job);
        cancelFlags.put(batchId, false);
        executor.submit(() -> run(batchId, teamId, resourceId, periods, timeout));
        return jobs.get(batchId);
    }

    private void run(String batchId, String teamId, String resourceId,
                     List<Long> periods, Duration timeout) {
        runningThreads.put(batchId, Thread.currentThread());
        Instant deadline = timeout == null ? null : Instant.now().plus(timeout);
        try {
            int committed = journal.committedCount(batchId);
            for (int i = committed; i < periods.size(); i++) {
                if (Boolean.TRUE.equals(cancelFlags.get(batchId)) || Thread.currentThread().isInterrupted()) {
                    update(batchId, committed, BatchStatus.CANCELLED);
                    return;
                }
                if (deadline != null && Instant.now().isAfter(deadline)) {
                    update(batchId, committed, BatchStatus.TIMED_OUT);
                    return;
                }
                long period = periods.get(i);
                CostEstimate estimate = estimationService.estimate(
                        teamId, resourceId, List.of(period), jobs.get(batchId).ruleVersion());
                journal.appendCommitted(batchId, estimate, i + 1);
                committed = i + 1;
                update(batchId, committed, BatchStatus.RUNNING);
            }
            update(batchId, committed, BatchStatus.COMMITTED);
        } catch (RuntimeException e) {
            update(batchId, journal.committedCount(batchId), BatchStatus.FAILED);
        } finally {
            runningThreads.remove(batchId);
        }
    }

    private void update(String batchId, int committed, BatchStatus status) {
        jobs.computeIfPresent(batchId, (k, job) -> job.withProgress(committed, status, Instant.now()));
    }

    /** 请求取消：设置标志并中断执行线程，收敛到 CANCELLED，无部分写入。 */
    public boolean cancel(String batchId) {
        BatchJob job = jobs.get(batchId);
        if (job == null || job.status() != BatchStatus.RUNNING) {
            return false;
        }
        cancelFlags.put(batchId, true);
        Thread t = runningThreads.get(batchId);
        if (t != null) {
            t.interrupt();
        }
        return true;
    }

    public Optional<BatchJob> job(String batchId) {
        return Optional.ofNullable(jobs.get(batchId));
    }

    /** 阻塞等待批次到达终态（测试与 API 共用）。 */
    public BatchJob await(String batchId, Duration wait) {
        Instant deadline = Instant.now().plus(wait);
        while (Instant.now().isBefore(deadline)) {
            BatchJob job = jobs.get(batchId);
            if (job == null || job.status() != BatchStatus.RUNNING) {
                return job;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return job;
            }
        }
        return jobs.get(batchId);
    }

    /**
     * 重启后恢复：从日志读取批次参数与最近已提交位置，继续未完成部分。
     * 已提交结果不重复计算、不丢失。
     */
    public BatchJob resume(String batchId) {
        BatchJob existing = jobs.get(batchId);
        if (existing != null && (existing.status() == BatchStatus.RUNNING
                || existing.status() == BatchStatus.COMMITTED)) {
            return existing;
        }
        // 终态但未完成（CANCELLED/TIMED_OUT/FAILED）：移除后从检查点继续
        jobs.remove(batchId);
        JournalEntry start = journal.batchStart(batchId)
                .orElseThrow(() -> new IllegalArgumentException("unknown batch: " + batchId));
        int committed = journal.committedCount(batchId);
        if (committed >= start. periods().size()) {
            BatchJob done = new BatchJob(batchId, start.periods(), committed,
                    BatchStatus.COMMITTED, start.ruleVersion(), Instant.now(), Instant.now());
            jobs.put(batchId, done);
            return done;
        }
        return submit(batchId, start.teamId(), start.resourceId(), start.periods(), null);
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
