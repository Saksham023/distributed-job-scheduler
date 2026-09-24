package com.jobscheduler.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.Map;

public record CreateJobRequest(
        @NotNull Long userId,
        @NotBlank String taskType,
        @NotNull Map<String, Object> params,
        @NotNull @Future OffsetDateTime scheduledAt
) {}