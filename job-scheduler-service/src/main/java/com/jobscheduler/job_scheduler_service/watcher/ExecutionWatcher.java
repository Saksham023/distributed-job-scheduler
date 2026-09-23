package com.jobscheduler.job_scheduler_service.watcher;

import com.jobscheduler.job_scheduler_service.config.WatcherProperties;
import com.jobscheduler.job_scheduler_service.messaging.JobExecutionPublisher;
import com.jobscheduler.job_scheduler_service.model.JobExecution;
import com.jobscheduler.job_scheduler_service.repository.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.watcher.enabled", havingValue = "true")
public class ExecutionWatcher {

    private static final Logger log = LoggerFactory.getLogger(ExecutionWatcher.class);

    private final JobExecutionRepository jobExecutionRepository;
    private final JobExecutionPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final WatcherProperties properties;

    public ExecutionWatcher(JobExecutionRepository jobExecutionRepository,
                            JobExecutionPublisher publisher,
                            TransactionTemplate transactionTemplate,
                            WatcherProperties properties) {
        this.jobExecutionRepository = jobExecutionRepository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.watcher.interval}")
    public void publishDueExecutions() {
        int totalQueued = 0;
        try {
            while (true) {
                BatchResult batch = transactionTemplate.execute(status -> publishNextBatch());
                totalQueued += batch.accepted();

                if (batch.selected() < JobExecutionPublisher.MAX_BATCH_SIZE) {
                    break;
                }
                if (batch.failed() > 0 && batch.failureRatio() >= properties.failureThreshold()) {
                    log.warn("Stopping watcher run: SQS rejected {} of {} messages in one batch",
                            batch.failed(), batch.selected());
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("Watcher run stopped: publishing to SQS failed; will retry next run", e);
        }

        if (totalQueued > 0) {
            log.info("Watcher run queued {} executions", totalQueued);
        }
    }

    private BatchResult publishNextBatch() {
        OffsetDateTime dueBefore = OffsetDateTime.now().plus(properties.lookahead());
        List<JobExecution> due = jobExecutionRepository.lockDueForPublishing(dueBefore, JobExecutionPublisher.MAX_BATCH_SIZE);
        if (due.isEmpty()) {
            return new BatchResult(0, 0);
        }

        Set<UUID> accepted = publisher.publishBatch(due);
        jobExecutionRepository.markQueued(accepted);
        return new BatchResult(due.size(), accepted.size());
    }

    private record BatchResult(int selected, int accepted) {

        int failed() {
            return selected - accepted;
        }

        double failureRatio() {
            return selected == 0 ? 0 : (double) failed() / selected;
        }
    }
}