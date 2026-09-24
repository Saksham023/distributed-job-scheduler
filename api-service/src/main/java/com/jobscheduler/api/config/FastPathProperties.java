package com.jobscheduler.api.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// A job due within this window is published to SQS as soon as it's created.
// Keep it equal to the watcher's app.watcher.lookahead.
@Validated
@ConfigurationProperties(prefix = "app.fast-path")
public record FastPathProperties(@NotNull Duration lookahead) {

    private static final Duration SQS_MAX_DELAY = Duration.ofMinutes(15);

    @AssertTrue(message = "lookahead must not exceed SQS's 15-minute maximum delay")
    public boolean isLookaheadWithinSqsLimit() {
        return lookahead == null || lookahead.compareTo(SQS_MAX_DELAY) <= 0;
    }
}
