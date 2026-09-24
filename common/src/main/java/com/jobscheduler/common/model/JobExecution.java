package com.jobscheduler.common.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JobExecution(
        UUID id,
        UUID jobId,
        OffsetDateTime scheduledAt,
        JobExecutionStatus status,
        Integer attempt,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt
) {}