package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.domain.Bill;
import com.github.cuixudong74.resourcecostforecastjava.domain.CostEstimate;
import com.github.cuixudong74.resourcecostforecastjava.estimation.EstimationService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/estimates")
public class EstimationController {

    private final EstimationService estimationService;

    public EstimationController(EstimationService estimationService) {
        this.estimationService = estimationService;
    }

    public record EstimateRequest(String teamId, String resourceId,
                                  Instant periodStart, Instant periodEnd,
                                  Long ruleVersion) {}

    @PostMapping
    public CostEstimate estimate(@RequestBody EstimateRequest request) {
        return estimationService.estimate(request.teamId(), request.resourceId(),
                request.periodStart(), request.periodEnd(), request.ruleVersion());
    }

    public record SettleRequest(String estimateId) {}

    @PostMapping("/settle")
    public Bill settle(@RequestBody SettleRequest request) {
        return estimationService.settle(request.estimateId());
    }

    @GetMapping("/bills")
    public List<Bill> bills() {
        return estimationService.bills();
    }

    @PostMapping("/bills/{billId}/recompute")
    public EstimationService.VersionRecompute recompute(@PathVariable String billId,
                                                        @RequestParam long ruleVersion) {
        return estimationService.recompute(billId, ruleVersion);
    }
}
