package com.jobscheduler.job_scheduler_service.messaging;

import java.util.UUID;

public record JobExecutionMessage(UUID jobExecutionId) {}