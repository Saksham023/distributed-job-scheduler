package com.jobscheduler.api.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// How far ahead a new recurring job's executions are created. The generator keeps
// topping up to the same horizon afterwards.
@Validated
@ConfigurationProperties(prefix = "app.recurring")
public record RecurringProperties(@NotNull Duration window) {
}