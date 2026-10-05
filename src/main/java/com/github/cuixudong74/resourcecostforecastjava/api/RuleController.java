package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.domain.BillingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleDiff;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleDiffService;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleStore ruleStore;
    private final RuleDiffService diffService;

    public RuleController(RuleStore ruleStore, RuleDiffService diffService) {
        this.ruleStore = ruleStore;
        this.diffService = diffService;
    }

    @PostMapping
    public BillingRule publish(@RequestBody BillingRule draft) {
        return ruleStore.publish(draft);
    }

    @GetMapping("/current")
    public BillingRule current() {
        return ruleStore.current();
    }

    @GetMapping("/{version}")
    public BillingRule get(@PathVariable long version) {
        return ruleStore.get(version)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version: " + version));
    }

    @GetMapping
    public List<BillingRule> history() {
        return ruleStore.history();
    }

    /** 同一份用量在两个版本下复算，给出可解释差异。 */
    @PostMapping("/diff")
    public RuleDiff diff(@RequestParam long from, @RequestParam long to,
                         @RequestBody List<UsagePoint> usage) {
        return diffService.diff(from, to, usage);
    }
}
