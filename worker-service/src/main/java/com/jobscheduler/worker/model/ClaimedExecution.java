package com.jobscheduler.worker.model;

import com.jobscheduler.common.model.ScheduleType;

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