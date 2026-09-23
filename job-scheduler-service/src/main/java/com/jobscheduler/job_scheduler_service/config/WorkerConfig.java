package com.jobscheduler.job_scheduler_service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {
}