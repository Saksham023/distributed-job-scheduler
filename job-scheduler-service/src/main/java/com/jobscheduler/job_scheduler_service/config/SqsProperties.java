package com.jobscheduler.job_scheduler_service.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.sqs")
public record SqsProperties(
        @NotBlank String region,
        @NotBlank String jobExecutionsQueueUrl
) {}