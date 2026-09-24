package com.jobscheduler.common.model;

import java.time.OffsetDateTime;
import java.util.UUID;

// The two things needed to publish an execution: which one, and when it's due.
public record ScheduledExecution(UUID id, OffsetDateTime scheduledAt) {
}