package com.jobscheduler.job_scheduler_service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(WatcherProperties.class)
public class WatcherConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "app.watcher.enabled", havingValue = "true")
    static class SchedulingConfig {
    }
}