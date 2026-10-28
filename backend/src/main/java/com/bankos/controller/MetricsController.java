package com.bankos.controller;

import com.bankos.dto.MetricsResponse;
import com.bankos.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** Processing times, anomaly counts and quality metrics. */
    @GetMapping
    public MetricsResponse metrics(@RequestParam String batchId) {
        return metricsService.metrics(batchId);
    }
}
