package com.jobscheduler.job_scheduler_service.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Job(
        UUID id,
        Long userId,
        Integer taskTypeId,
        String params,
        ScheduleType scheduleType,
        JobStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}