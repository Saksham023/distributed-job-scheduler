package com.jobscheduler.job_scheduler_service.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.watcher")
public record WatcherProperties(
        boolean enabled,
        @NotNull Duration interval,
        @NotNull Duration lookahead,
        @DecimalMin("0.0") @DecimalMax("1.0") double failureThreshold
) {

    private static final Duration SQS_MAX_DELAY = Duration.ofMinutes(15);

    @AssertTrue(message = "lookahead must not exceed SQS's 15-minute maximum delay")
    public boolean isLookaheadWithinSqsLimit() {
        return lookahead == null || lookahead.compareTo(SQS_MAX_DELAY) <= 0;
    }

    @AssertTrue(message = "lookahead must be longer than interval, so every job is seen before it's due")
    public boolean isLookaheadLongerThanInterval() {
        return lookahead == null || interval == null || lookahead.compareTo(interval) > 0;
    }
}