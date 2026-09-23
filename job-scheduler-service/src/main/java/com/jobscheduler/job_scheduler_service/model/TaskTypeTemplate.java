package com.jobscheduler.job_scheduler_service.model;

public record TaskTypeTemplate(
        Integer taskTypeId,
        Integer templateId,
        String paramsSchema
) {}