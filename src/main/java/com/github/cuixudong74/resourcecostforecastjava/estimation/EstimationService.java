package com.github.cuixudong74.resourcecostforecastjava.estimation;

import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import com.github.cuixudong74.resourcecostforecastjava.domain.Bill;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 估算编排：从 UsageStore 取数、按指定（或当前）规则版本估算、
 * 结算生成不可变账单、跨版本复算并给出差异解释。
 */
@Service
public class EstimationService {

    private final UsageStore usageStore;
    private final RuleStore ruleStore;
    private final CostEstimator estimator;
    private final AppProperties properties;

    /** 已产出的估算（未结算），按 estimateId 索引。 */
    private final ConcurrentHashMap<String, CostEstimate> estimates = new ConcurrentHashMap<>();
    /** 已结算账单，结算后不可变。 */
    private final ConcurrentHashMap<String, Bill> bills = new ConcurrentHashMap<>();
    /** estimateId -> billId，保证同一估算不重复结算。 */
    private final ConcurrentHashMap<String, String> settledByEstimate = new ConcurrentHashMap<>();

    public EstimationService(UsageStore usageStore, RuleStore ruleStore,
                             CostEstimator estimator, AppProperties properties) {
        this.usageStore = usageStore;
        this.ruleStore = ruleStore;
        this.estimator = estimator;
        this.properties = properties;
    }

    /** 按指定规则版本估算；ruleVersion 为 null 时使用当前版本。 */
    public CostEstimate estimate(String teamId, String resourceId,
                                 Instant periodStart, Instant periodEnd,
                                 Long ruleVersion) {
        RuleSet rules = resolve(ruleVersion);
        List<UsageRecord> usages = usageStore.query(teamId, resourceId, periodStart, periodEnd);
        Instant graceDeadline = periodEnd.plusMillis(properties.getLateGraceMillis());
        String estimateId = UUID.randomUUID().toString();
        CostEstimate estimate = estimator.estimate(estimateId, teamId, resourceId,
                usages, rules, periodStart, periodEnd, graceDeadline);
        estimates.put(estimateId, estimate);
        return estimate;
    }

    /** 直接对给定估算入册（供批处理器提交已算好的结果）。 */
    public void register(CostEstimate estimate) {
        estimates.putIfAbsent(estimate.estimateId(), estimate);
    }

    public Optional<CostEstimate> estimate(String estimateId) {
        return Optional.ofNullable(estimates.get(estimateId));
    }

    /** 结算：把一次估算固化为不可变账单，幂等（同 estimateId 不重复结算）。 */
    public Bill settle(String estimateId) {
        String existingBillId = settledByEstimate.get(estimateId);
        if (existingBillId != null) {
            return bills.get(existingBillId);
        }
        CostEstimate estimate = estimates.get(estimateId);
        if (estimate == null) {
            throw new IllegalArgumentException("unknown estimateId: " + estimateId);
        }
        synchronized (settledByEstimate) {
            existingBillId = settledByEstimate.get(estimateId);
            if (existingBillId != null) {
                return bills.get(existingBillId);
            }
            // 账单固化估算时的规则版本，后续规则变更不得改写。
            Bill bill = new Bill(UUID.randomUUID().toString(), estimate.ruleVersion(),
                    estimate, Instant.now());
            bills.put(bill.billId(), bill);
            settledByEstimate.put(estimateId, bill.billId());
            return bill;
        }
    }

    public Optional<Bill> bill(String billId) {
        return Optional.ofNullable(bills.get(billId));
    }

    public List<Bill> bills() {
        return List.copyOf(bills.values());
    }

    /**
     * 同一用量在新版本规则下复算，返回金额差与解释。
     * 历史账单本身保持不变，复算结果仅是假设性对比。
     */
    public VersionRecompute recompute(String billId, long newRuleVersion) {
        Bill bill = bill(billId)
                .orElseThrow(() -> new IllegalArgumentException("unknown billId: " + billId));
        RuleSet newRules = ruleStore.version(newRuleVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version: " + newRuleVersion));
        CostEstimate original = bill.estimate();
        List<UsageRecord> usages = usageStore.query(original.teamId(), original.resourceId(),
                original.periodStart(), original.periodEnd());
        Instant graceDeadline = original.periodEnd().plusMillis(properties.getLateGraceMillis());
        CostEstimate recomputed = estimator.estimate(UUID.randomUUID().toString(),
                original.teamId(), original.resourceId(), usages, newRules,
                original.periodStart(), original.periodEnd(), graceDeadline);

        BigDecimal delta = recomputed.amount().subtract(original.amount());
        List<String> explanations = new ArrayList<>();
        RuleSet oldRules = ruleStore.version(bill.ruleVersion()).orElse(null);
        if (oldRules != null) {
            RuleSet.RuleDiff diff = oldRules.diff(newRules);
            diff.changed().forEach(c -> explanations.add(c.explain()));
            diff.addedResources().forEach(r -> explanations.add("added resource rule: " + r));
            diff.removedResources().forEach(r -> explanations.add("removed resource rule: " + r));
        }
        explanations.add("amount " + original.amount() + " (ruleVersion=" + bill.ruleVersion()
                + ") -> " + recomputed.amount() + " (ruleVersion=" + newRuleVersion
                + "), delta=" + delta);
        return new VersionRecompute(bill, recomputed, delta, List.copyOf(explanations));
    }

    private RuleSet resolve(Long ruleVersion) {
        if (ruleVersion == null) {
            return ruleStore.current();
        }
        return ruleStore.version(ruleVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version: " + ruleVersion));
    }

    /** 复算对比结果：历史账单金额不变，仅给出假设性差异。 */
    public record VersionRecompute(Bill originalBill, CostEstimate recomputed,
                                   BigDecimal amountDelta, List<String> explanations) {
    }
}
