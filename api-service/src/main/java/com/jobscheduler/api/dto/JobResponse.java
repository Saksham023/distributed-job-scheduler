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
        OffsetDateTime scheduledAt,
        JobExecutionStatus executionStatus,
        OffsetDateTime createdAt
) {}