package com.jobscheduler.api.dto;

import com.jobscheduler.common.model.JobExecutionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ExecutionResponse(
        UUID id,
        OffsetDateTime scheduledAt,
        JobExecutionStatus status,
        int attempt,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
) {
}
