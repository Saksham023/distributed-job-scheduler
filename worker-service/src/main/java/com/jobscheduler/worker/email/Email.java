package com.jobscheduler.worker.email;

public record Email(String to, String subject, String body) {}