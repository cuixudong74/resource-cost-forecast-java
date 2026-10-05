package com.github.cuixudong74.resourcecostforecastjava.rules;

import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 纯函数计费引擎：给定完整规则版本与用量计算成本，无副作用。
 * 计费语义：max(0, 用量 - 免费额度) × 单价，再按规则指定的 scale/rounding 舍入。
 */
@Component
public class BillingEngine {

    public BigDecimal priceOf(BillingRule rule, UsagePoint point) {
        if (point.isMissing()) {
            return BigDecimal.ZERO.setScale(rule.scale(), rule.roundingMode());
        }
        BigDecimal quantity = BigDecimal.valueOf(point.value());
        BigDecimal billable = quantity.subtract(rule.freeTierPerResource()).max(BigDecimal.ZERO);
        BigDecimal cost = billable.multiply(rule.unitPriceOf(point.metric()));
        return cost.setScale(rule.scale(), rule.roundingMode());
    }

    public BigDecimal totalOf(BillingRule rule, List<UsagePoint> points) {
        BigDecimal total = BigDecimal.ZERO;
        for (UsagePoint p : points) {
            total = total.add(priceOf(rule, p));
        }
        return total.setScale(rule.scale(), rule.roundingMode());
    }
}
