package com.jobscheduler.job_scheduler_service.dto;

import com.jobscheduler.job_scheduler_service.model.JobExecutionStatus;
import com.jobscheduler.job_scheduler_service.model.JobStatus;
import com.jobscheduler.job_scheduler_service.model.ScheduleType;

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
        OffsetDateTime scheduledAt,
        JobExecutionStatus executionStatus,
        OffsetDateTime createdAt
) {}