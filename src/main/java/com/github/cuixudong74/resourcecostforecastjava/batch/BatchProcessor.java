package com.github.cuixudong74.resourcecostforecastjava.batch;

import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.estimation.CostEstimator;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 批处理器：支持取消与超时；取消/超时后不留部分写入；
 * 同一 jobId 重复提交安全收敛到同一结果；重启后从最近已提交位置继续。
 */
@Service
public class BatchProcessor {

    private final UsageStore usageStore;
    private final RuleStore ruleStore;
    private final CostEstimator estimator;
    private final EstimationService estimationService;
    private final CheckpointStore checkpointStore;
    private final AppProperties properties;

    /** jobId -> 最终/当前结果。COMMITTED 结果同时在磁盘日志中。 */
    private final ConcurrentHashMap<String, BatchOutcome> outcomes = new ConcurrentHashMap<>();
    /** jobId -> 运行状态机（仅进行中的批次）。 */
    private final ConcurrentHashMap<String, AtomicReference<BatchStatus>> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public BatchProcessor(UsageStore usageStore, RuleStore ruleStore, CostEstimator estimator,
                          EstimationService estimationService, CheckpointStore checkpointStore,
                          AppProperties properties) {
        this.usageStore = usageStore;
        this.ruleStore = ruleStore;
        this.estimator = estimator;
        this.estimationService = estimationService;
        this.checkpointStore = checkpointStore;
        this.properties = properties;
        recover();
    }

    /**
     * 提交批次（幂等）。
     * - 已 COMMITTED：直接返回已提交结果，不重复计算、不重复计费。
     * - 进行中：返回当前 RUNNING 状态。
     * - 否则执行计算，成功则原子提交（状态机 CAS + 磁盘日志），
     *   超时/取消则丢弃全部中间结果，不落盘。
     */
    public BatchOutcome submit(BatchJob job, Duration timeout) {
        BatchOutcome existing = outcomes.get(job.jobId());
        if (existing != null && existing.status() == BatchStatus.COMMITTED) {
            return existing; // 幂等：重复触发收敛到同一已提交结果
        }
        AtomicReference<BatchStatus> state =
                running.computeIfAbsent(job.jobId(), id -> new AtomicReference<>(BatchStatus.PENDING));
        if (!state.compareAndSet(BatchStatus.PENDING, BatchStatus.RUNNING)) {
            // 已有同名批次在进行或已终结
            BatchOutcome current = outcomes.get(job.jobId());
            if (current != null) {
                return current;
            }
            return new BatchOutcome(job.jobId(), state.get(), null,
                    "batch already in progress", Instant.now());
        }

        long timeoutMillis = timeout != null ? timeout.toMillis() : properties.getBatchTimeoutMillis();
        Future<CostEstimate> future = executor.submit(() -> compute(job));
        futures.put(job.jobId(), future);
        try {
            CostEstimate estimate = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            // 只有仍处 RUNNING 才允许提交；若已被取消/判超时，结果直接丢弃。
            if (state.compareAndSet(BatchStatus.RUNNING, BatchStatus.COMMITTED)) {
                BatchOutcome committed = new BatchOutcome(job.jobId(), BatchStatus.COMMITTED,
                        estimate, "committed", Instant.now());
                // 先落盘（可恢复），再入内存与估算服务。
                checkpointStore.appendCommitted(committed);
                estimationService.register(estimate);
                outcomes.put(job.jobId(), committed);
                return committed;
            }
            BatchOutcome discarded = new BatchOutcome(job.jobId(), state.get(), null,
                    "result discarded: batch was " + state.get(), Instant.now());
            outcomes.put(job.jobId(), discarded);
            return discarded;
        } catch (TimeoutException e) {
            transitionToTerminal(job.jobId(), state, future, BatchStatus.TIMED_OUT,
                    "timed out after " + timeoutMillis + "ms");
            return outcomes.get(job.jobId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transitionToTerminal(job.jobId(), state, future, BatchStatus.CANCELLED, "interrupted");
            return outcomes.get(job.jobId());
        } catch (java.util.concurrent.CancellationException e) {
            // future 被 cancel() 取消：状态机已置为 CANCELLED，直接返回该终态。
            BatchOutcome cancelled = outcomes.get(job.jobId());
            if (cancelled == null) {
                cancelled = new BatchOutcome(job.jobId(), BatchStatus.CANCELLED, null,
                        "cancelled; no partial writes", Instant.now());
                outcomes.put(job.jobId(), cancelled);
            }
            return cancelled;
        } catch (ExecutionException e) {
            transitionToTerminal(job.jobId(), state, future, BatchStatus.FAILED,
                    String.valueOf(e.getCause()));
            return outcomes.get(job.jobId());
        } finally {
            futures.remove(job.jobId());
            running.remove(job.jobId());
        }
    }

    /** 取消进行中的批次；已提交的不受影响。 */
    public boolean cancel(String jobId) {
        AtomicReference<BatchStatus> state = running.get(jobId);
        if (state == null) {
            return false;
        }
        boolean transitioned = state.compareAndSet(BatchStatus.RUNNING, BatchStatus.CANCELLED)
                || state.compareAndSet(BatchStatus.PENDING, BatchStatus.CANCELLED);
        if (transitioned) {
            Future<?> future = futures.get(jobId);
            if (future != null) {
                future.cancel(true);
            }
            // 取消只记录内存状态，不写磁盘：重启后视为从未发生，可安全重试。
            outcomes.put(jobId, new BatchOutcome(jobId, BatchStatus.CANCELLED, null,
                    "cancelled; no partial writes", Instant.now()));
        }
        return transitioned;
    }

    public Optional<BatchOutcome> outcome(String jobId) {
        return Optional.ofNullable(outcomes.get(jobId));
    }

    public List<BatchOutcome> outcomes() {
        return List.copyOf(outcomes.values());
    }

    /**
     * 重启恢复：重新加载已提交结果，丢弃未完成批次。
     * 恢复后已提交与未完成批次可区分，且已提交结果不会丢失或重复计费。
     */
    public synchronized void recover() {
        for (BatchOutcome committed : checkpointStore.loadCommitted()) {
            // 磁盘日志按提交顺序，jobId 唯一：putIfAbsent 防重复计费。
            outcomes.putIfAbsent(committed.jobId(), committed);
            if (committed.estimate() != null) {
                estimationService.register(committed.estimate());
            }
        }
    }

    /** 测试钩子：人为拉长计算耗时以确定性触发超时/取消。 */
    private volatile long computeDelayMillis = 0L;

    public void setComputeDelayMillis(long delayMillis) {
        this.computeDelayMillis = delayMillis;
    }

    private CostEstimate compute(BatchJob job) {
        if (computeDelayMillis > 0) {
            try {
                Thread.sleep(computeDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("compute interrupted", e);
            }
        }
        RuleSet rules = job.ruleVersion() != null
                ? ruleStore.version(job.ruleVersion())
                    .orElseThrow(() -> new IllegalArgumentException("unknown rule version"))
                : ruleStore.current();
        List<UsageRecord> usages = usageStore.query(job.teamId(), job.resourceId(),
                job.periodStart(), job.periodEnd());
        Instant graceDeadline = job.periodEnd().plusMillis(properties.getLateGraceMillis());
        // estimateId 由 jobId 确定性派生：重试产生同一估算标识。
        return estimator.estimate("est-" + job.jobId(), job.teamId(), job.resourceId(),
                usages, rules, job.periodStart(), job.periodEnd(), graceDeadline);
    }

    private void transitionToTerminal(String jobId, AtomicReference<BatchStatus> state,
                                      Future<?> future, BatchStatus terminal, String detail) {
        if (state.compareAndSet(BatchStatus.RUNNING, terminal)
                || state.compareAndSet(BatchStatus.PENDING, terminal)) {
            future.cancel(true);
            // 终态只记录在内存，不写磁盘：未提交批次重启后不可见，可安全重跑。
            outcomes.put(jobId, new BatchOutcome(jobId, terminal, null, detail, Instant.now()));
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
