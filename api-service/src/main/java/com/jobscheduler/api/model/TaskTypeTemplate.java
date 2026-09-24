package com.jobscheduler.api.model;

public record TaskTypeTemplate(
        Integer taskTypeId,
        Integer templateId,
        String paramsSchema
) {}