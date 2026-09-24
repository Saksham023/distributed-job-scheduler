package com.jobscheduler.api.exception;

public record FieldViolation(String field, String message) {}