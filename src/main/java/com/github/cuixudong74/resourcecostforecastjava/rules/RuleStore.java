package com.github.cuixudong74.resourcecostforecastjava.rules;

import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则版本仓库。
 *
 * 并发语义：当前版本保存在 AtomicReference 中，发布时先构造完整不可变对象
 * 再一次原子替换；读者拿到的引用永远是某个已提交的完整版本，
 * 不会出现字段级混合。历史版本保存在 ConcurrentHashMap，发布后只读。
 */
@Component
public class RuleStore {

    private final AtomicReference<BillingRule> current;
    private final ConcurrentHashMap<Long, BillingRule> history = new ConcurrentHashMap<>();

    public RuleStore() {
        // 默认 v1：全零价目，保证系统启动即有可读版本。
        BillingRule initial = new BillingRule(1L, Instant.now(), Map.of(),
                BigDecimal.ZERO, 2, RoundingMode.HALF_UP, "initial-empty-rule");
        this.current = new AtomicReference<>(initial);
        this.history.put(1L, initial);
    }

    /**
     * 基于当前版本发布新版本：以 draft 的字段为准，版本号自动 +1。
     * synchronized 保证版本号分配与历史登记是串行的（不丢版本）；
     * 对读者的可见性切换仍是单次原子引用替换。
     */
    public synchronized BillingRule publish(BillingRule draft) {
        BillingRule base = current.get();
        BillingRule next = new BillingRule(
                base.version() + 1,
                Instant.now(),
                draft.unitPrices(),
                draft.freeTierPerResource(),
                draft.scale(),
                draft.roundingMode(),
                draft.description());
        // 先登记历史再切换当前指针，保证 current 必然存在于 history。
        history.put(next.version(), next);
        current.set(next);
        return next;
    }

    /** 读取当前版本（原子快照，永远是一个完整版本）。 */
    public BillingRule current() {
        return current.get();
    }

    public Optional<BillingRule> get(long version) {
        return Optional.ofNullable(history.get(version));
    }

    public List<BillingRule> history() {
        return history.values().stream()
                .sorted((a, b) -> Long.compare(a.version(), b.version()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
