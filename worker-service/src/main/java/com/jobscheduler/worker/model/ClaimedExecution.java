package com.jobscheduler.worker.model;

import com.jobscheduler.common.model.ScheduleType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimedExecution(
        UUID executionId,
        UUID jobId,
        OffsetDateTime scheduledAt,
        int attempt,                // this claim's attempt number
        ScheduleType scheduleType,
        String params,
        Integer templateId,
        String subject,
        String body
) {}