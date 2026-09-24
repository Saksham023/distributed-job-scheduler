package com.jobscheduler.common.messaging;

import java.util.UUID;

public record JobExecutionMessage(UUID jobExecutionId) {}