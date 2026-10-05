package com.github.cuixudong74.resourcecostforecastjava.estimation;

import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostLineItem;
import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason;
import com.github.cuixudong74.resourcecostforecastjava.domain.SkippedUsage;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯函数估算器：给定用量与某一完整规则版本，产出可解释的估算结果。
 * 缺失/迟到数据按原因跳过，不补零。
 */
@Component
public class CostEstimator {

    /**
     * @param usages            待估算用量（可含 MISSING 占位）
     * @param ruleSet           某一完整规则版本快照
     * @param lateGraceDeadline 迟到宽限截止：LATE 记录若晚于该时间到达则跳过
     */
    public CostEstimate estimate(String estimateId, String teamId, String resourceId,
                                 List<UsageRecord> usages, RuleSet ruleSet,
                                 Instant periodStart, Instant periodEnd,
                                 Instant lateGraceDeadline) {
        List<CostLineItem> items = new ArrayList<>();
        List<SkippedUsage> skipped = new ArrayList<>();
        double billedQuantity = 0.0d;
        BigDecimal amount = BigDecimal.ZERO;

        for (UsageRecord usage : usages) {
            if (usage.status() == UsageStatus.MISSING) {
                // 缺失：跳过并记录原因，绝不用零值冒充。
                skipped.add(new SkippedUsage(usage.recordId(), SkipReason.MISSING_DATA,
                        usage.arrivedAt()));
                continue;
            }
            if (usage.status() == UsageStatus.LATE
                    && lateGraceDeadline != null
                    && usage.arrivedAt().isAfter(lateGraceDeadline)) {
                skipped.add(new SkippedUsage(usage.recordId(), SkipReason.LATE_BEYOND_GRACE,
                        usage.arrivedAt()));
                continue;
            }
            PricingRule rule = ruleSet.ruleFor(usage.resourceId());
            if (rule == null) {
                throw new IllegalStateException(
                        "no pricing rule for resource " + usage.resourceId()
                                + " in rule version " + ruleSet.version());
            }
            double freeQuota = rule.freeQuota() == null ? 0.0d : rule.freeQuota().doubleValue();
            double billable = Math.max(0.0d, usage.quantity() - freeQuota);
            BigDecimal lineAmount = rule.unitPrice()
                    .multiply(BigDecimal.valueOf(billable))
                    .setScale(6, RoundingMode.HALF_UP);
            String explanation = "quantity=" + usage.quantity()
                    + " - freeQuota=" + freeQuota
                    + " => billable=" + billable
                    + " * unitPrice=" + rule.unitPrice()
                    + " (ruleVersion=" + ruleSet.version() + ")";
            items.add(new CostLineItem(usage.recordId(), usage.resourceId(), billable,
                    rule.unitPrice(), lineAmount, explanation));
            billedQuantity += billable;
            amount = amount.add(lineAmount);
        }

        return new CostEstimate(estimateId, teamId, resourceId, ruleSet.version(),
                periodStart, periodEnd, billedQuantity, amount,
                items, skipped, Instant.now());
    }
}
