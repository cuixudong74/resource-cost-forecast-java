package com.github.cuixudong74.resourcecostforecastjava.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提交日志：append-only JSONL 文件。
 *
 * 恢复语义：构造时重放日志重建内存索引——已提交估算按 estimateId 去重，
 * 每批次 committedCount 取日志中最大值。重启后未提交的部分与已提交结果
 * 可区分，且同一 estimateId 不会被重复追加（不得重复计费）。
 */
@Component
public class JournalStore {

    private final Path journalFile;
    private final ObjectMapper mapper;
    private final Map<String, CostEstimate> committedByEstimateId = new ConcurrentHashMap<>();
    private final Map<String, Integer> committedCountByBatch = new ConcurrentHashMap<>();
    private final Map<String, JournalEntry> startByBatch = new ConcurrentHashMap<>();

    public JournalStore(@Value("${journal.dir:./data/journal}") String journalDir) {
        this.journalFile = Path.of(journalDir).resolve("journal.jsonl");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        replay();
    }

    private synchronized void replay() {
        if (!Files.exists(journalFile)) {
            return;
        }
        try (var lines = Files.lines(journalFile)) {
            lines.filter(l -> !l.isBlank()).forEach(line -> {
                try {
                    apply(mapper.readValue(line, JournalEntry.class));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void apply(JournalEntry entry) {
        if ("START".equals(entry.kind())) {
            startByBatch.putIfAbsent(entry.batchId(), entry);
        } else if ("COMMIT".equals(entry.kind())) {
            committedByEstimateId.putIfAbsent(entry.estimate().estimateId(), entry.estimate());
            committedCountByBatch.merge(entry.batchId(), entry.committedCount(), Math::max);
        }
    }

    public synchronized void recordStart(String batchId, String teamId, String resourceId,
                                         List<Long> periods, long ruleVersion) {
        if (startByBatch.containsKey(batchId)) {
            return; // 幂等：重复触发同批次不重复记录
        }
        JournalEntry entry = JournalEntry.start(batchId, teamId, resourceId, periods, ruleVersion);
        append(entry);
        apply(entry);
    }

    /** 追加一条已提交估算。estimateId 已存在时跳过（幂等收敛）。 */
    public synchronized void appendCommitted(String batchId, CostEstimate estimate, int committedCount) {
        if (committedByEstimateId.containsKey(estimate.estimateId())) {
            committedCountByBatch.merge(batchId, committedCount, Math::max);
            return;
        }
        JournalEntry entry = JournalEntry.commit(batchId, committedCount, estimate);
        append(entry);
        apply(entry);
    }

    private void append(JournalEntry entry) {
        try {
            Files.createDirectories(journalFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(journalFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(mapper.writeValueAsString(entry));
                w.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<CostEstimate> committedEstimates(String batchId) {
        // 已提交结果按 estimateId 全局去重后，过滤出属于该批次的部分无法直接区分，
        // 因此按批次维度另行重放日志。
        List<CostEstimate> result = new ArrayList<>();
        if (!Files.exists(journalFile)) {
            return result;
        }
        try (var lines = Files.lines(journalFile)) {
            lines.filter(l -> !l.isBlank()).forEach(line -> {
                try {
                    JournalEntry e = mapper.readValue(line, JournalEntry.class);
                    if ("COMMIT".equals(e.kind()) && e.batchId().equals(batchId)) {
                        result.add(e.estimate());
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    public Optional<CostEstimate> findByEstimateId(String estimateId) {
        return Optional.ofNullable(committedByEstimateId.get(estimateId));
    }

    public int committedCount(String batchId) {
        return committedCountByBatch.getOrDefault(batchId, 0);
    }

    public Optional<JournalEntry> batchStart(String batchId) {
        return Optional.ofNullable(startByBatch.get(batchId));
    }
}
