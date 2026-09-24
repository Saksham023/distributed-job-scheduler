package com.jobscheduler.watcher.config;

import com.jobscheduler.common.config.SqsPublisherConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WatcherProperties.class)
@Import(SqsPublisherConfig.class)
public class WatcherConfig {
}