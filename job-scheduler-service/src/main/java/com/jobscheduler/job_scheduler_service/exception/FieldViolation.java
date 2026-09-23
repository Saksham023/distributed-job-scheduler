package com.jobscheduler.job_scheduler_service.exception;

public record FieldViolation(String field, String message) {}