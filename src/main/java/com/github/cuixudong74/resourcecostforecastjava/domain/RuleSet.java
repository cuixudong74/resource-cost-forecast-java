package com.github.cuixudong74.resourcecostforecastjava.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 计费规则的不可变完整快照。读取端任意时刻只能拿到某个已提交的完整版本，
 * 不会出现字段级混合；发布后内容永不修改。
 */
public record RuleSet(
        long version,
        Instant committedAt,
        Map<String, PricingRule> rules) {

    public RuleSet {
        rules = Map.copyOf(rules);
    }

    public PricingRule ruleFor(String resourceId) {
        return rules.get(resourceId);
    }

    /** 与另一版本对比，给出可解释差异。 */
    public RuleDiff diff(RuleSet other) {
        return RuleDiff.between(this, other);
    }

    /** 两个规则版本之间的差异说明。 */
    public record RuleDiff(
            long fromVersion,
            long toVersion,
            List<String> addedResources,
            List<String> removedResources,
            List<RuleChange> changed) {

        public static RuleDiff between(RuleSet from, RuleSet to) {
            java.util.List<String> added = new java.util.ArrayList<>();
            java.util.List<String> removed = new java.util.ArrayList<>();
            java.util.List<RuleChange> changed = new java.util.ArrayList<>();
            for (String id : to.rules().keySet()) {
                if (!from.rules().containsKey(id)) added.add(id);
            }
            for (String id : from.rules().keySet()) {
                if (!to.rules().containsKey(id)) removed.add(id);
            }
            for (String id : to.rules().keySet()) {
                PricingRule oldR = from.rules().get(id);
                PricingRule newR = to.rules().get(id);
                if (oldR != null && !oldR.equals(newR)) {
                    changed.add(new RuleChange(id, oldR, newR));
                }
            }
            return new RuleDiff(from.version(), to.version(),
                    List.copyOf(added), List.copyOf(removed), List.copyOf(changed));
        }
    }

    public record RuleChange(String resourceId, PricingRule oldRule, PricingRule newRule) {
        public String explain() {
            return "resource=" + resourceId
                    + " unitPrice " + oldRule.unitPrice() + " -> " + newRule.unitPrice()
                    + ", freeQuota " + oldRule.freeQuota() + " -> " + newRule.freeQuota();
        }
    }
}
