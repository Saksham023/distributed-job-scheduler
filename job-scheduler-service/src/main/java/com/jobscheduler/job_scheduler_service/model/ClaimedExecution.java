package com.jobscheduler.job_scheduler_service.model;

import java.util.UUID;

public record ClaimedExecution(
        UUID executionId,
        UUID jobId,
        ScheduleType scheduleType,
        String params,
        Integer templateId,
        String subject,
        String body
) {}