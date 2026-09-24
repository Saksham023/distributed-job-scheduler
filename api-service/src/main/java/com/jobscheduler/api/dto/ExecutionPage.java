package com.jobscheduler.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

// Newest first. nextBefore is null on the last page; otherwise pass it as ?before=
// to get the next (older) page.
public record ExecutionPage(List<ExecutionResponse> executions, OffsetDateTime nextBefore) {
}
