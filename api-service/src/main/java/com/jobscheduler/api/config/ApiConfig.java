package com.jobscheduler.api.config;

import com.jobscheduler.common.config.SqsPublisherConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties({FastPathProperties.class, RecurringProperties.class})
@Import(SqsPublisherConfig.class)
public class ApiConfig {
}
