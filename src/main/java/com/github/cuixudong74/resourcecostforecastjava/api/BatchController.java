package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.batch.BatchJob;
import com.github.cuixudong74.resourcecostforecastjava.batch.BatchOutcome;
import com.github.cuixudong74.resourcecostforecastjava.batch.BatchProcessor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchProcessor batchProcessor;

    public BatchController(BatchProcessor batchProcessor) {
        this.batchProcessor = batchProcessor;
    }

    public record SubmitRequest(BatchJob job, Long timeoutMillis) {}

    @PostMapping
    public BatchOutcome submit(@RequestBody SubmitRequest request) {
        Duration timeout = request.timeoutMillis() != null
                ? Duration.ofMillis(request.timeoutMillis()) : null;
        return batchProcessor.submit(request.job(), timeout);
    }

    @PostMapping("/{jobId}/cancel")
    public boolean cancel(@PathVariable String jobId) {
        return batchProcessor.cancel(jobId);
    }

    @GetMapping
    public List<BatchOutcome> outcomes() {
        return batchProcessor.outcomes();
    }

    @PostMapping("/recover")
    public void recover() {
        batchProcessor.recover();
    }
}
