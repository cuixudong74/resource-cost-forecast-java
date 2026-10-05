package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.batch.BatchRunner;
import com.github.cuixudong74.resourcecostforecastjava.domain.BatchJob;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchRunner batchRunner;

    public BatchController(BatchRunner batchRunner) {
        this.batchRunner = batchRunner;
    }

    @PostMapping
    public BatchJob submit(@RequestParam String batchId, @RequestParam String teamId,
                           @RequestParam String resourceId, @RequestParam List<Long> periods,
                           @RequestParam(required = false) Long timeoutMillis) {
        return batchRunner.submit(batchId, teamId, resourceId, periods,
                timeoutMillis == null ? null : Duration.ofMillis(timeoutMillis));
    }

    @PostMapping("/{batchId}/cancel")
    public boolean cancel(@PathVariable String batchId) {
        return batchRunner.cancel(batchId);
    }

    @PostMapping("/{batchId}/resume")
    public BatchJob resume(@PathVariable String batchId) {
        return batchRunner.resume(batchId);
    }

    @GetMapping("/{batchId}")
    public BatchJob job(@PathVariable String batchId) {
        return batchRunner.job(batchId)
                .orElseThrow(() -> new IllegalArgumentException("unknown batch: " + batchId));
    }
}
