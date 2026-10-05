package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.domain.UsageRecord;
import com.github.cuixudong74.resourcecostforecastjava.usage.IngestResult;
import com.github.cuixudong74.resourcecostforecastjava.usage.UsageStore;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageStore usageStore;

    public UsageController(UsageStore usageStore) {
        this.usageStore = usageStore;
    }

    @PostMapping
    public IngestResult ingest(@RequestBody UsageRecord record) {
        return usageStore.ingest(record);
    }

    public record MissingRequest(String recordId, String teamId, String resourceId,
                                 Instant periodStart, Instant periodEnd, String unit) {}

    @PostMapping("/missing")
    public IngestResult markMissing(@RequestBody MissingRequest request) {
        return usageStore.markMissing(request.recordId(), request.teamId(), request.resourceId(),
                request.periodStart(), request.periodEnd(), request.unit());
    }

    @GetMapping
    public List<UsageRecord> query(@RequestParam String teamId,
                                   @RequestParam String resourceId,
                                   @RequestParam Instant from,
                                   @RequestParam Instant to) {
        return usageStore.query(teamId, resourceId, from, to);
    }
}
