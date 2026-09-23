package com.jobscheduler.job_scheduler_service.model;

public enum JobExecutionStatus {
    PENDING,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}