package com.jobscheduler.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.Map;

// Exactly one of scheduledAt (one-time) or cronExpression (recurring) must be
// present; the cross-field rules are checked when the request is turned into
// a schedule, not here.
public record CreateJobRequest(
        @NotNull Long userId,
        @NotBlank String taskType,
        @NotNull Map<String, Object> params,

        // One-time
        @Future OffsetDateTime scheduledAt,

        // Recurring
        String cronExpression,
        String timezone,
        OffsetDateTime startsAt,
        @Future OffsetDateTime endsAt,
        @Positive Integer maxOccurrences
) {}