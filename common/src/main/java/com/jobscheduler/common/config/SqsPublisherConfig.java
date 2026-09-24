package com.jobscheduler.common.config;

import com.jobscheduler.common.messaging.JobExecutionPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

// Imported by the services that publish to SQS (API, watcher); the worker consumes through Spring Cloud AWS instead.
@Configuration
@EnableConfigurationProperties(SqsProperties.class)
public class SqsPublisherConfig {

    @Bean
    public SqsClient sqsClient(SqsProperties properties) {
        return SqsClient.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    public JobExecutionPublisher jobExecutionPublisher(SqsClient sqsClient, JsonMapper jsonMapper, SqsProperties properties) {
        return new JobExecutionPublisher(sqsClient, jsonMapper, properties);
    }
}