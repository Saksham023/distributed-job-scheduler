package com.jobscheduler.job_scheduler_service.messaging;

import com.jobscheduler.job_scheduler_service.config.SqsProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class JobExecutionPublisher {

    private static final int MAX_DELAY_SECONDS = 900;

    private final SqsClient sqsClient;
    private final JsonMapper jsonMapper;
    private final String queueUrl;

    public JobExecutionPublisher(SqsClient sqsClient, JsonMapper jsonMapper, SqsProperties properties) {
        this.sqsClient = sqsClient;
        this.jsonMapper = jsonMapper;
        this.queueUrl = properties.jobExecutionsQueueUrl();
    }

    public void publish(UUID jobExecutionId, OffsetDateTime scheduledAt) {
        String body = jsonMapper.writeValueAsString(new JobExecutionMessage(jobExecutionId));

        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .delaySeconds(delaySecondsUntil(scheduledAt))
                .build());
    }

    private int delaySecondsUntil(OffsetDateTime scheduledAt) {
        long seconds = Duration.between(OffsetDateTime.now(), scheduledAt).getSeconds();
        return Math.clamp(seconds, 0, MAX_DELAY_SECONDS);
    }
}