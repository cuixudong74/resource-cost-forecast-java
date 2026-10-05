package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastConfig;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastResult;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastStrategy;
import com.github.cuixudong74.resourcecostforecastjava.domain.RuleSet;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.config.AppProperties;
import com.github.cuixudong74.resourcecostforecastjava.forecast.Forecaster;
import com.github.cuixudong74.resourcecostforecastjava.rules.RuleStore;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final Forecaster forecaster;
    private final UsageStore usageStore;
    private final RuleStore ruleStore;
    private final AppProperties properties;

    public ForecastController(Forecaster forecaster, UsageStore usageStore,
                              RuleStore ruleStore, AppProperties properties) {
        this.forecaster = forecaster;
        this.usageStore = usageStore;
        this.ruleStore = ruleStore;
        this.properties = properties;
    }

    public record ForecastRequest(String teamId, String resourceId,
                                  Instant historyFrom, Instant historyTo,
                                  Instant firstFuturePeriodStart, long periodMillis,
                                  Integer window, ForecastStrategy strategy,
                                  Integer horizon, Long seed, Long ruleVersion) {}

    @PostMapping
    public ForecastResult forecast(@RequestBody ForecastRequest request) {
        List<UsageRecord> history = usageStore.query(request.teamId(), request.resourceId(),
                request.historyFrom(), request.historyTo());
        RuleSet rules = request.ruleVersion() != null
                ? ruleStore.version(request.ruleVersion())
                    .orElseThrow(() -> new IllegalArgumentException("unknown rule version"))
                : ruleStore.current();
        ForecastConfig config = new ForecastConfig(
                request.window() != null ? request.window() : properties.getForecastWindow(),
                request.strategy() != null ? request.strategy() : ForecastStrategy.MOVING_AVERAGE,
                request.horizon() != null ? request.horizon() : 3,
                request.seed() != null ? request.seed() : properties.getForecastSeed());
        return forecaster.forecast(request.teamId(), request.resourceId(), history,
                config, rules, request.firstFuturePeriodStart(), request.periodMillis());
    }
}
