package com.jobscheduler.job_scheduler_service.model;

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
        OffsetDateTime createdAt
) {}