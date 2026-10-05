package com.github.cuixudong74.resourcecostforecastjava.estimation;

import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostLineItem;
import com.github.cuixudong74.resourcecostforecastjava.domain.DataQuality;
import com.github.cuixudong74.resourcecostforecastjava.domain.EstimateStatus;
import com.github.cuixudong74.resourcecostforecastjava.domain.SettledBill;
import com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason;
import com.github.cuixudong74.resourcecostforecastjava.domain.SkippedItem;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.rules.BillingEngine;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 估算服务。
 *
 * - 估算时一次性取得规则版本快照，整个估算过程归属该版本（可追溯）；
 * - 缺失/迟到的用量跳过并记录原因与原始到达时间，不用 0 冒充；
 * - 已结算周期返回冻结的结算账单，后续规则变更不得改写；
 * - estimateId 由输入确定性生成，重复触发收敛到同一结果（幂等）。
 */
@Service
public class EstimationService {

    private final RuleStore ruleStore;
    private final BillingEngine engine;
    private final UsageStore usageStore;
    /** 迟到截止时刻：到达时间晚于它的用量视为 LATE。可为 null（不判定迟到）。 */
    private volatile java.time.Instant lateCutoff;

    /** 已结算账单：key = team|resource|period，写入后不可变。 */
    private final Map<String, SettledBill> settledBills = new ConcurrentHashMap<>();
    /** 已完成的估算：estimateId -> estimate，保证重复触发幂等。 */
    private final Map<String, CostEstimate> estimates = new ConcurrentHashMap<>();

    public EstimationService(RuleStore ruleStore, BillingEngine engine, UsageStore usageStore) {
        this.ruleStore = ruleStore;
        this.engine = engine;
        this.usageStore = usageStore;
    }

    public void setLateCutoff(Instant lateCutoff) {
        this.lateCutoff = lateCutoff;
    }

    public static String estimateId(String teamId, String resourceId, List<Long> periods, long ruleVersion) {
        String raw = teamId + "|" + resourceId + "|" + periods + "|" + ruleVersion;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 估算成本。ruleVersion 为 null 时使用当前版本快照。
     * 已结算的周期不参与重算，直接引用结算账单金额。
     */
    public CostEstimate estimate(String teamId, String resourceId, List<Long> periods, Long ruleVersion) {
        BillingRule rule = ruleVersion != null
                ? ruleStore.get(ruleVersion).orElseThrow(
                        () -> new IllegalArgumentException("unknown rule version: " + ruleVersion))
                : ruleStore.current();

        String id = estimateId(teamId, resourceId, periods, rule.version());
        CostEstimate existing = estimates.get(id);
        if (existing != null) {
            return existing; // 幂等：相同输入返回同一结果
        }

        List<CostLineItem> lines = new ArrayList<>();
        List<SkippedItem> skipped = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (long period : periods) {
            Optional<SettledBill> settled = settledBill(teamId, resourceId, period);
            if (settled.isPresent()) {
                // 已结算：引用冻结金额，归属结算时的规则版本，不被改写。
                SettledBill bill = settled.get();
                lines.add(new CostLineItem(teamId, resourceId, "settled", period,
                        1.0, bill.amount(), bill.amount()));
                total = total.add(bill.amount());
                continue;
            }
            for (String seriesKey : usageStore.knownSeries()) {
                String[] parts = seriesKey.split("\\|");
                if (!parts[0].equals(teamId) || !parts[1].equals(resourceId)) {
                    continue;
                }
                String metric = parts[2];
                UsagePoint p = usageStore.series(teamId, resourceId, metric, period, period, lateCutoff).get(0);
                if (p.quality() == DataQuality.MISSING) {
                    skipped.add(new SkippedItem(resourceId, metric, period, SkipReason.MISSING_DATA, null));
                    continue;
                }
                if (p.quality() == DataQuality.LATE) {
                    skipped.add(new SkippedItem(resourceId, metric, period, SkipReason.LATE_DATA, p.arrivedAt()));
                    continue;
                }
                BigDecimal cost = engine.priceOf(rule, p);
                lines.add(new CostLineItem(teamId, resourceId, metric, period,
                        p.value(), rule.unitPriceOf(metric), cost));
                total = total.add(cost);
            }
        }

        EstimateStatus status = skipped.isEmpty() ? EstimateStatus.COMPLETED : EstimateStatus.PARTIAL;
        CostEstimate estimate = new CostEstimate(id, rule.version(), lines, skipped,
                total.setScale(rule.scale(), rule.roundingMode()), status, Instant.now());
        estimates.putIfAbsent(id, estimate);
        return estimates.get(id);
    }

    /** 结算：以当前规则版本计算并冻结金额，幂等（已结算则直接返回原账单）。 */
    public SettledBill settle(String teamId, String resourceId, long period) {
        String key = teamId + "|" + resourceId + "|" + period;
        return settledBills.computeIfAbsent(key, k -> {
            CostEstimate est = estimate(teamId, resourceId, List.of(period), null);
            return new SettledBill("bill-" + est.estimateId(), teamId, resourceId, period,
                    est.ruleVersion(), est.total(), Instant.now());
        });
    }

    public Optional<SettledBill> settledBill(String teamId, String resourceId, long period) {
        return Optional.ofNullable(settledBills.get(teamId + "|" + resourceId + "|" + period));
    }
}
