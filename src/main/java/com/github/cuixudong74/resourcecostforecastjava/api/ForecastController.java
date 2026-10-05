package com.github.cuixudong74.resourcecostforecastjava.api;

import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastConfig;
import com.github.cuixudong74.resourcecostforecastjava.domain.ForecastResult;
import com.github.cuixudong74.resourcecostforecastjava.forecast.ForecastService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forecasts")
public class ForecastController {

    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @PostMapping
    public ForecastResult forecast(@RequestParam String teamId, @RequestParam String resourceId,
                                   @RequestParam String metric,
                                   @RequestParam long fromPeriod, @RequestParam long toPeriod,
                                   @RequestBody ForecastConfig config) {
        return forecastService.forecast(teamId, resourceId, metric, fromPeriod, toPeriod, config);
    }
}
