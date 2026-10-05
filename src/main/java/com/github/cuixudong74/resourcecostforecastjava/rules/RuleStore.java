package com.github.cuixudong74.resourcecostforecastjava.rules;

import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 版本化规则存储。发布是原子的：任何并发读取只能看到某个已提交的完整
 * RuleSet 快照，不会出现字段级混合。历史版本永久保留以便复算与追溯。
 */
@Component
public class RuleStore {

    /** 当前已提交版本，原子切换，读者永远拿到完整快照。 */
    private final AtomicReference<RuleSet> current =
            new AtomicReference<>(new RuleSet(0L, Instant.EPOCH, Map.of()));

    /** 全部已提交版本（不可变快照），供复算与追溯。 */
    private final ConcurrentNavigableMap<Long, RuleSet> history = new ConcurrentSkipListMap<>();

    public RuleStore() {
        history.put(0L, current.get());
    }

    /** 发布新版本规则，返回新版本号。版本号单调递增。 */
    public synchronized RuleSet publish(List<PricingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("rules must not be empty");
        }
        Map<String, PricingRule> byResource = new LinkedHashMap<>();
        for (PricingRule rule : rules) {
            if (rule.resourceId() == null || rule.resourceId().isBlank()) {
                throw new IllegalArgumentException("rule resourceId must not be blank");
            }
            if (rule.unitPrice() == null || rule.unitPrice().signum() < 0) {
                throw new IllegalArgumentException("unitPrice must be >= 0 for " + rule.resourceId());
            }
            byResource.put(rule.resourceId(), rule);
        }
        long nextVersion = current.get().version() + 1;
        RuleSet next = new RuleSet(nextVersion, Instant.now(), byResource);
        // 先写入历史，再原子切换当前指针：读者要么看到旧完整版本，要么看到新完整版本。
        history.put(nextVersion, next);
        current.set(next);
        return next;
    }

    /** 当前已提交的最新版本。 */
    public RuleSet current() {
        return current.get();
    }

    /** 取指定历史版本。 */
    public Optional<RuleSet> version(long version) {
        return Optional.ofNullable(history.get(version));
    }

    /** 所有已提交版本号（升序）。 */
    public List<Long> versions() {
        return List.copyOf(history.keySet());
    }
}
