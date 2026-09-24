package com.jobscheduler.generator.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// A schedule is topped up once fewer than `refillBelow` of its runs lie ahead, and then
// filled up to `window` ahead. Refilling only below a threshold (instead of every run)
// means each schedule is written about once per (window - refillBelow), not every minute.
@Validated
@ConfigurationProperties(prefix = "app.generator")
public record GeneratorProperties(
        @NotNull Duration interval,
        @NotNull Duration window,       // keep equal to the API's app.recurring.window
        @NotNull Duration refillBelow,  // must stay above interval + the watcher's lookahead
        @Min(1) int batchSize
) {

    @AssertTrue(message = "refill-below must be shorter than window")
    public boolean isRefillBelowShorterThanWindow() {
        return refillBelow == null || window == null || refillBelow.compareTo(window) < 0;
    }

    @AssertTrue(message = "refill-below must be longer than interval, so runs are created before they're needed")
    public boolean isRefillBelowLongerThanInterval() {
        return refillBelow == null || interval == null || refillBelow.compareTo(interval) > 0;
    }
}
