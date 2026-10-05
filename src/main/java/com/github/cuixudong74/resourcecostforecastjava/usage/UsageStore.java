package com.github.cuixudong74.resourcecostforecastjava.usage;

import com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用量存储。按 recordId 幂等去重；保留原始到达时间；
 * 缺失账期以 MISSING 占位记录表示，绝不用零值冒充。
 */
@Component
public class UsageStore {

    private final ConcurrentHashMap<String, UsageRecord> records = new ConcurrentHashMap<>();

    /**
     * 摄入一条用量，重复 recordId 安全去重。
     * 若该 recordId 已有 MISSING 占位，则迟到数据替换占位（保留真实到达时间）。
     */
    public IngestResult ingest(UsageRecord record) {
        if (record.recordId() == null || record.recordId().isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }
        if (record.arrivedAt() == null) {
            throw new IllegalArgumentException("arrivedAt must be preserved");
        }
        UsageRecord effective = record;
        if (record.status() != UsageStatus.MISSING
                && record.arrivedAt().isAfter(record.periodEnd())) {
            // 到达时间晚于账期结束 => 标记为迟到，但保留数据与原始到达时间。
            effective = new UsageRecord(record.recordId(), record.teamId(), record.resourceId(),
                    record.periodStart(), record.periodEnd(), record.quantity(), record.unit(),
                    record.arrivedAt(), UsageStatus.LATE);
        }
        final UsageRecord toStore = effective;
        UsageRecord[] previous = new UsageRecord[1];
        records.compute(record.recordId(), (id, existing) -> {
            previous[0] = existing;
            if (existing == null) {
                return toStore;
            }
            if (existing.status() == UsageStatus.MISSING && toStore.status() != UsageStatus.MISSING) {
                // 迟到数据补登：替换缺失占位。
                return toStore;
            }
            // 已存在真实数据（或重复占位）：幂等去重，不覆盖。
            return existing;
        });
        if (previous[0] == null) {
            return new IngestResult(record.recordId(), true, SkipReason.NONE);
        }
        if (previous[0].status() == UsageStatus.MISSING && toStore.status() != UsageStatus.MISSING) {
            return new IngestResult(record.recordId(), true, SkipReason.NONE);
        }
        return new IngestResult(record.recordId(), false, SkipReason.DUPLICATE_RECORD);
    }

    /** 为缺失账期登记占位记录（quantity 恒为 0 且不计费）。 */
    public IngestResult markMissing(String recordId, String teamId, String resourceId,
                                    Instant periodStart, Instant periodEnd, String unit) {
        UsageRecord placeholder = new UsageRecord(recordId, teamId, resourceId,
                periodStart, periodEnd, 0.0d, unit, null, UsageStatus.MISSING);
        UsageRecord existing = records.putIfAbsent(recordId, placeholder);
        return existing == null
                ? new IngestResult(recordId, true, SkipReason.NONE)
                : new IngestResult(recordId, false, SkipReason.DUPLICATE_RECORD);
    }

    /** 查询某团队某资源在区间内的全部记录（含缺失占位），按账期开始排序。 */
    public List<UsageRecord> query(String teamId, String resourceId, Instant from, Instant to) {
        return records.values().stream()
                .filter(r -> r.teamId().equals(teamId))
                .filter(r -> resourceId == null || r.resourceId().equals(resourceId))
                .filter(r -> !r.periodStart().isBefore(from) && r.periodStart().isBefore(to))
                .sorted(Comparator.comparing(UsageRecord::periodStart))
                .toList();
    }
}
