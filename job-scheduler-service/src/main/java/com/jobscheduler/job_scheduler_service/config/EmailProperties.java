package com.jobscheduler.job_scheduler_service.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
        @NotBlank String provider,
        @NotBlank @Email String from
) {}