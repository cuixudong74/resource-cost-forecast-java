package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;

/** 单资源的计费规则：单价 + 免费额度。 */
public record PricingRule(
        String resourceId,
        String unit,
        BigDecimal unitPrice,
        BigDecimal freeQuota) {
}
