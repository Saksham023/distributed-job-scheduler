package com.jobscheduler.generator.model;

import java.time.OffsetDateTime;
import java.util.UUID;

// A recurring schedule that needs more executions created.
public record DueSchedule(
        UUID jobId,
        String cronExpression,
        String timezone,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,          // null = no end
        Integer maxOccurrences,         // null = no limit
        int occurrencesGenerated,
        OffsetDateTime generatedUntil   // every run up to this instant already exists
) {
}
