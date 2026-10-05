package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.math.BigDecimal;

/** 一条用量对应的计费明细，可解释。 */
public record CostLineItem(
        String recordId,
        String resourceId,
        double billedQuantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        String explanation) {
}
