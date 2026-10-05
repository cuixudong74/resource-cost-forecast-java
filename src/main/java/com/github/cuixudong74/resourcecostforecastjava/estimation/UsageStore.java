package com.github.cuixudong74.resourcecostforecastjava.estimation;

import com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用量仓库。
 *
 * 缺失与迟到语义：
 * - 每条记录保留原始到达时间 arrivedAt；
 * - 读取时以 lateCutoff 判定：arrivedAt 晚于 cutoff 的点标记为 LATE；
 * - 范围内没有任何记录的周期生成 MISSING 占位（value=null），绝不用 0 冒充。
 */
@Component
public class UsageStore {

    /** key = teamId|resourceId|metric|period */
    private final Map<String, UsagePoint> points = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> knownKeys = new CopyOnWriteArrayList<>();

    private static String key(String teamId, String resourceId, String metric, long period) {
        return teamId + "|" + resourceId + "|" + metric + "|" + period;
    }

    /** 幂等写入：同一 (team, resource, metric, period) 重复上报保留最早到达时间。 */
    public void ingest(UsagePoint point) {
        String k = key(point.teamId(), point.resourceId(), point.metric(), point.periodEpoch());
        points.merge(k, point, (old, neu) ->
                old.arrivedAt().isBefore(neu.arrivedAt()) ? old : neu);
        knownKeys.addIfAbsent(point.teamId() + "|" + point.resourceId() + "|" + point.metric());
    }

    /**
     * 读取 [fromPeriod, toPeriod] 闭区间的序列，缺失周期补 MISSING 占位。
     * lateCutoff 非空时，到达时间晚于 cutoff 的点标记为 LATE。
     */
    public List<UsagePoint> series(String teamId, String resourceId, String metric,
                                   long fromPeriod, long toPeriod, Instant lateCutoff) {
        List<UsagePoint> result = new ArrayList<>();
        for (long p = fromPeriod; p <= toPeriod; p++) {
            UsagePoint stored = points.get(key(teamId, resourceId, metric, p));
            if (stored == null) {
                result.add(new UsagePoint(teamId, resourceId, metric, p, null, null, DataQuality.MISSING));
            } else if (lateCutoff != null && stored.arrivedAt() != null && stored.arrivedAt().isAfter(lateCutoff)) {
                result.add(new UsagePoint(teamId, resourceId, metric, p,
                        stored.value(), stored.arrivedAt(), DataQuality.LATE));
            } else {
                result.add(stored);
            }
        }
        return result;
    }

    /** 已知的 (team|resource|metric) 组合，供批量任务枚举。 */
    public List<String> knownSeries() {
        return List.copyOf(knownKeys);
    }
}
