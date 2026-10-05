package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.domain.SettledBill;
import com.github.cuixudong74.resourcecostforecastjava.domain.UsagePoint;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import com.github.cuixudong74.resourcecostforecastjava.estimation.UsageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class EstimateController {

    private final EstimationService estimationService;
    private final UsageStore usageStore;

    public EstimateController(EstimationService estimationService, UsageStore usageStore) {
        this.estimationService = estimationService;
        this.usageStore = usageStore;
    }

    @PostMapping("/usage")
    public void ingest(@RequestBody UsagePoint point) {
        usageStore.ingest(point);
    }

    @PostMapping("/estimates")
    public CostEstimate estimate(@RequestParam String teamId, @RequestParam String resourceId,
                                 @RequestParam List<Long> periods,
                                 @RequestParam(required = false) Long ruleVersion) {
        return estimationService.estimate(teamId, resourceId, periods, ruleVersion);
    }

    @PostMapping("/settlements")
    public SettledBill settle(@RequestParam String teamId, @RequestParam String resourceId,
                              @RequestParam long period) {
        return estimationService.settle(teamId, resourceId, period);
    }

    @GetMapping("/settlements")
    public SettledBill settledBill(@RequestParam String teamId, @RequestParam String resourceId,
                                   @RequestParam long period) {
        return estimationService.settledBill(teamId, resourceId, period)
                .orElseThrow(() -> new IllegalArgumentException("no settled bill"));
    }
}
