package com.jobscheduler.api.model;

import com.jobscheduler.common.model.JobExecutionStatus;
import com.jobscheduler.common.model.JobStatus;
import com.jobscheduler.common.model.ScheduleType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JobDetails(
        UUID id,
        Long userId,
        String taskType,
        String params,
        ScheduleType scheduleType,
        JobStatus status,
        OffsetDateTime scheduledAt,
        JobExecutionStatus executionStatus,
        OffsetDateTime createdAt,
        // Recurring jobs only (null for one-time jobs)
        String cronExpression,
        String timezone,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer maxOccurrences,
        Integer occurrencesGenerated
) {
}
