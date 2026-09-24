package com.jobscheduler.worker;

import com.jobscheduler.worker.config.WorkerProperties;
import com.jobscheduler.worker.email.Email;
import com.jobscheduler.worker.email.EmailSender;
import com.jobscheduler.worker.model.ClaimedExecution;
import com.jobscheduler.common.model.JobExecutionStatus;
import com.jobscheduler.common.model.ScheduleType;
import com.jobscheduler.worker.repository.JobExecutionRepository;
import com.jobscheduler.worker.repository.JobRepository;
import com.jobscheduler.worker.template.RenderedTemplate;
import com.jobscheduler.worker.template.TemplateRenderer;
import com.samskivert.mustache.MustacheException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class ExecutionProcessor {

    public enum Outcome { DELETE_MESSAGE, KEEP_MESSAGE }

    private static final Logger log = LoggerFactory.getLogger(ExecutionProcessor.class);
    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};

    private final JobExecutionRepository executionRepository;
    private final JobRepository jobRepository;
    private final TemplateRenderer templateRenderer;
    private final EmailSender emailSender;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final WorkerProperties workerProperties;

    public ExecutionProcessor(JobExecutionRepository executionRepository,
                              JobRepository jobRepository,
                              TemplateRenderer templateRenderer,
                              EmailSender emailSender,
                              TransactionTemplate transactionTemplate,
                              JsonMapper jsonMapper,
                              WorkerProperties workerProperties) {
        this.executionRepository = executionRepository;
        this.jobRepository = jobRepository;
        this.templateRenderer = templateRenderer;
        this.emailSender = emailSender;
        this.transactionTemplate = transactionTemplate;
        this.jsonMapper = jsonMapper;
        this.workerProperties = workerProperties;
    }

    public Outcome process(UUID executionId, int receiveCount) {
        long start = System.nanoTime();
        Optional<ClaimedExecution> claimed = executionRepository.claim(executionId);
        long claimedAt = System.nanoTime();
        if (claimed.isEmpty()) {
            return handleNotClaimed(executionId, receiveCount);
        }
        ClaimedExecution execution = claimed.get();
        long lateMs = Duration.between(execution.scheduledAt(), OffsetDateTime.now()).toMillis();
        log.debug("event=claimed execution={} attempt={} receiveCount={} lateMs={}",
                executionId, execution.attempt(), receiveCount, lateMs);

        try {
            sendEmail(execution);
        } catch (RuntimeException e) {
            return isPermanent(e) ? failPermanently(execution, e) : handleTransient(execution, receiveCount, e);
        }
        long sentAt = System.nanoTime();
        // Logged before COMPLETED is written: an execution with "sent" but no "completed" line
        // (e.g. its worker was killed in between) is the one way a duplicate email can happen.
        log.debug("event=sent execution={}", executionId);

        transactionTemplate.executeWithoutResult(status -> {
            executionRepository.markCompleted(execution.executionId());
            if (execution.scheduleType() == ScheduleType.ONE_TIME) {
                jobRepository.markCompleted(execution.jobId());
            }
        });
        long completedAt = System.nanoTime();
        log.info("Execution {} completed: attempt={} lateMs={} claimMs={} sendMs={} completeMs={}",
                executionId, execution.attempt(), lateMs, millis(start, claimedAt), millis(claimedAt, sentAt),
                millis(sentAt, completedAt));
        return Outcome.DELETE_MESSAGE;
    }

    private static long millis(long fromNanos, long toNanos) {
        return Duration.ofNanos(toNanos - fromNanos).toMillis();
    }

    private void sendEmail(ClaimedExecution execution) {
        Map<String, Object> params = jsonMapper.readValue(execution.params(), PARAMS_TYPE);
        RenderedTemplate rendered = templateRenderer.render(
                execution.templateId(), execution.subject(), execution.body(), params);
        emailSender.send(new Email((String) params.get("to"), rendered.subject(), rendered.htmlBody()));
    }

    private Outcome handleNotClaimed(UUID executionId, int receiveCount) {
        Optional<JobExecutionStatus> status = executionRepository.findStatus(executionId);
        log.debug("event=not-claimed execution={} status={} receiveCount={}",
                executionId, status.map(Enum::name).orElse("MISSING"), receiveCount);
        if (status.isEmpty()) {
            log.warn("Execution {} not found; deleting its message", executionId);
            return Outcome.DELETE_MESSAGE;
        }
        return switch (status.get()) {
            case PROCESSING -> {
                executionRepository.resetProcessingToQueued(executionId);
                log.info("Execution {} was PROCESSING; reset to QUEUED, message left for redelivery", executionId);
                yield Outcome.KEEP_MESSAGE;
            }
            // Became claimable again between our claim and this read (another worker reset it).
            case PENDING, QUEUED -> Outcome.KEEP_MESSAGE;
            case COMPLETED, FAILED, CANCELLED -> Outcome.DELETE_MESSAGE;
        };
    }

    // Retrying can't fix these. Anything not listed is treated as transient and retried.
    private static boolean isPermanent(RuntimeException e) {
        return e instanceof MustacheException
                || e instanceof JacksonException
                || e instanceof IllegalArgumentException
                || e instanceof MailParseException
                || e instanceof MailPreparationException;
    }

    private Outcome failPermanently(ClaimedExecution execution, RuntimeException e) {
        markFailed(execution, describe(e));
        log.error("Execution {} failed permanently", execution.executionId(), e);
        return Outcome.DELETE_MESSAGE;
    }

    private Outcome handleTransient(ClaimedExecution execution, int receiveCount, RuntimeException e) {
        if (receiveCount >= workerProperties.maxReceiveCount()) {
            markFailed(execution, "Gave up after " + receiveCount + " attempts: " + describe(e));
            log.error("Execution {} failed on its last attempt; the message goes to the DLQ", execution.executionId(), e);
        } else {
            executionRepository.markForRetry(execution.executionId(), describe(e));
            log.warn("Execution {} failed on attempt {}; it will be retried", execution.executionId(), receiveCount, e);
        }
        return Outcome.KEEP_MESSAGE;
    }

    private void markFailed(ClaimedExecution execution, String error) {
        transactionTemplate.executeWithoutResult(status -> {
            executionRepository.markFailed(execution.executionId(), error);
            if (execution.scheduleType() == ScheduleType.ONE_TIME) {
                jobRepository.markFailed(execution.jobId());
            }
        });
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}