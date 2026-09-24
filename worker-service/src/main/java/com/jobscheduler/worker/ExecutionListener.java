package com.jobscheduler.worker;

import com.jobscheduler.common.messaging.JobExecutionMessage;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionListener.class);

    private final ExecutionProcessor processor;

    public ExecutionListener(ExecutionProcessor processor) {
        this.processor = processor;
    }

    @SqsListener(value = "${app.sqs.job-executions-queue-url}", acknowledgementMode = "MANUAL")
    public void onMessage(JobExecutionMessage message,
                          @Header(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT) int receiveCount,
                          Acknowledgement acknowledgement) {
        try {
            if (processor.process(message.jobExecutionId(), receiveCount) == ExecutionProcessor.Outcome.DELETE_MESSAGE) {
                acknowledgement.acknowledge();
            }
        } catch (RuntimeException e) {
            // e.g. the database is unreachable: keep the message; SQS redelivers it after the visibility timeout.
            log.error("Could not process execution {}; message left for redelivery", message.jobExecutionId(), e);
        }
    }
}