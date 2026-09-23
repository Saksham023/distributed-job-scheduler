package com.jobscheduler.job_scheduler_service.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.worker")
public record WorkerProperties(
        boolean enabled,
        @Min(1) int maxReceiveCount
) {}