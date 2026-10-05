package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.domain.PricingRule;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleStore ruleStore;

    public RuleController(RuleStore ruleStore) {
        this.ruleStore = ruleStore;
    }

    public record PublishRequest(List<PricingRule> rules) {}

    @PostMapping
    public RuleSet publish(@RequestBody PublishRequest request) {
        return ruleStore.publish(request.rules());
    }

    @GetMapping("/current")
    public RuleSet current() {
        return ruleStore.current();
    }

    @GetMapping("/{version}")
    public RuleSet version(@PathVariable long version) {
        return ruleStore.version(version)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version: " + version));
    }

    @GetMapping
    public List<Long> versions() {
        return ruleStore.versions();
    }

    @GetMapping("/diff")
    public RuleSet.RuleDiff diff(@RequestParam long from, @RequestParam long to) {
        RuleSet a = version(from);
        RuleSet b = version(to);
        return a.diff(b);
    }
}
