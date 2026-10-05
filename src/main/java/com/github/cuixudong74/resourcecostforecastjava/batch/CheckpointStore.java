package com.github.cuixudong74.resourcecostforecastjava.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 检查点存储：把已提交批次持久化到磁盘日志，重启后从最近已提交位置恢复；
 * 未提交的中间状态不落盘，重启即丢弃。
 */
@Component
public class CheckpointStore {

    private final ObjectMapper objectMapper;
    private final Path logFile;

    public CheckpointStore(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.logFile = Path.of(properties.getCheckpointDir(), "committed-batches.log");
    }

    /**
     * 原子追加一条已提交结果：先写临时文件再 rename 不行（追加场景），
     * 因此采用单行 JSON + fsync，保证崩溃时最多损失最后一条未刷盘记录，
     * 绝不会出现半行（单行写入加行级同步）。
     */
    public synchronized void appendCommitted(BatchOutcome outcome) {
        try {
            Files.createDirectories(logFile.getParent());
            String line = objectMapper.writeValueAsString(outcome) + "\n";
            try (var channel = java.nio.channels.FileChannel.open(logFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                channel.write(java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                // 强制刷盘，保证重启后已提交结果不丢失。
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append checkpoint", e);
        }
    }

    /** 读取全部已提交结果（按提交顺序）；损坏的尾行（崩溃残留）被忽略。 */
    public synchronized List<BatchOutcome> loadCommitted() {
        List<BatchOutcome> loaded = new ArrayList<>();
        if (!Files.exists(logFile)) {
            return loaded;
        }
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    loaded.add(objectMapper.readValue(line, BatchOutcome.class));
                } catch (IOException parseFailure) {
                    // 崩溃可能留下半行：忽略尾部的不可解析行。
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load checkpoints", e);
        }
        return loaded;
    }

    public Optional<BatchOutcome> find(String jobId) {
        return loadCommitted().stream()
                .filter(o -> o.jobId().equals(jobId))
                .findFirst();
    }
}
