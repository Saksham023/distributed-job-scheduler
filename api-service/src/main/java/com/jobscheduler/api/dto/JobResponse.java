package com.jobscheduler.api.dto;

import com.jobscheduler.common.model.JobExecutionStatus;
import com.jobscheduler.common.model.JobStatus;
import com.jobscheduler.common.model.ScheduleType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record JobResponse(
        UUID id,
        Long userId,
        String taskType,
        Map<String, Object> params,
        ScheduleType scheduleType,
        JobStatus status,
        // The next run that hasn't finished yet, or the most recent run if none is left.
        // For a one-time job: its only run.
        OffsetDateTime scheduledAt,
        JobExecutionStatus executionStatus,
        Recurrence recurrence,          // null for one-time jobs
        OffsetDateTime createdAt
) {

    public record Recurrence(
            String cronExpression,
            String timezone,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Integer maxOccurrences,
            int occurrencesGenerated
    ) {
    }
}
