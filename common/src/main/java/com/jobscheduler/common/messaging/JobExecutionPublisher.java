package com.jobscheduler.common.messaging;

import com.jobscheduler.common.config.SqsProperties;
import com.jobscheduler.common.model.ScheduledExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class JobExecutionPublisher {

    public static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_DELAY_SECONDS = 900;
    private static final Logger log = LoggerFactory.getLogger(JobExecutionPublisher.class);

    private final SqsClient sqsClient;
    private final JsonMapper jsonMapper;
    private final String queueUrl;

    public JobExecutionPublisher(SqsClient sqsClient, JsonMapper jsonMapper, SqsProperties properties) {
        this.sqsClient = sqsClient;
        this.jsonMapper = jsonMapper;
        this.queueUrl = properties.jobExecutionsQueueUrl();
    }

    public Set<UUID> publishBatch(List<ScheduledExecution> executions) {
        if (executions.isEmpty()) {
            return Set.of();
        }
        if (executions.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "SQS accepts at most " + MAX_BATCH_SIZE + " messages per batch, got " + executions.size());
        }

        List<SendMessageBatchRequestEntry> entries = executions.stream()
                .map(execution -> SendMessageBatchRequestEntry.builder()
                        .id(execution.id().toString())
                        .messageBody(toMessageBody(execution.id()))
                        .delaySeconds(delaySecondsUntil(execution.scheduledAt()))
                        .build())
                .toList();

        SendMessageBatchResponse response = sqsClient.sendMessageBatch(SendMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(entries)
                .build());

        response.failed().forEach(failure -> log.warn(
                "SQS rejected execution {}: {} ({})", failure.id(), failure.message(), failure.code()));

        return response.successful().stream()
                .map(success -> UUID.fromString(success.id()))
                .collect(Collectors.toSet());
    }

    private String toMessageBody(UUID jobExecutionId) {
        return jsonMapper.writeValueAsString(new JobExecutionMessage(jobExecutionId));
    }

    private int delaySecondsUntil(OffsetDateTime scheduledAt) {
        long seconds = Duration.between(OffsetDateTime.now(), scheduledAt).getSeconds();
        return Math.clamp(seconds, 0, MAX_DELAY_SECONDS);
    }
}