package com.jobscheduler.generator;

import com.jobscheduler.common.schedule.Cron;
import com.jobscheduler.common.schedule.Occurrences;
import com.jobscheduler.generator.config.GeneratorProperties;
import com.jobscheduler.generator.model.DueSchedule;
import com.jobscheduler.generator.repository.JobExecutionRepository;
import com.jobscheduler.generator.repository.JobRepository;
import com.jobscheduler.generator.repository.RecurringScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

// Every interval: tops up the executions of recurring schedules that are running low, then
// completes recurring jobs whose schedule has ended. Executions are created well ahead of
// time; the watcher publishes them as usual.
@Component
public class ExecutionGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionGenerator.class);

    private final RecurringScheduleRepository scheduleRepository;
    private final JobExecutionRepository executionRepository;
    private final JobRepository jobRepository;
    private final TransactionTemplate transactionTemplate;
    private final GeneratorProperties properties;

    public ExecutionGenerator(RecurringScheduleRepository scheduleRepository,
                              JobExecutionRepository executionRepository,
                              JobRepository jobRepository,
                              TransactionTemplate transactionTemplate,
                              GeneratorProperties properties) {
        this.scheduleRepository = scheduleRepository;
        this.executionRepository = executionRepository;
        this.jobRepository = jobRepository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.generator.interval}")
    public void generate() {
        long startNanos = System.nanoTime();
        int schedules = 0;
        int created = 0;
        int completed = 0;
        try {
            while (true) {
                BatchResult batch = transactionTemplate.execute(status -> topUpNextBatch());
                schedules += batch.schedules();
                created += batch.created();
                if (batch.schedules() < properties.batchSize()) {
                    break;
                }
            }
            long completeStart = System.nanoTime();
            completed = scheduleRepository.completeFinishedJobs();
            log.debug("event=complete completed={} ms={}", completed,
                    Duration.ofNanos(System.nanoTime() - completeStart).toMillis());
        } catch (RuntimeException e) {
            log.error("Generator run stopped; will retry next run", e);
        }

        if (schedules > 0 || completed > 0) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            log.info("Generator run topped up {} schedules ({} executions created) and completed {} jobs in {} ms",
                    schedules, created, completed, elapsedMs);
        }
    }

    private BatchResult topUpNextBatch() {
        long start = System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime horizon = now.plus(properties.window());
        List<DueSchedule> due = scheduleRepository.lockDueForTopUp(now.plus(properties.refillBelow()),
                properties.batchSize());

        int created = 0;
        for (DueSchedule schedule : due) {
            created += topUp(schedule, now, horizon);
        }
        if (!due.isEmpty()) {
            log.debug("event=batch schedules={} created={} ms={}", due.size(), created,
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
        return new BatchResult(due.size(), created);
    }

    private int topUp(DueSchedule schedule, OffsetDateTime now, OffsetDateTime horizon) {
        CronExpression cron;
        ZoneId zone;
        try {
            cron = Cron.parse(schedule.cronExpression());
            zone = ZoneId.of(schedule.timezone());
        } catch (RuntimeException e) {
            // Only possible with bad data written outside the API (which validates both).
            // Retrying can't fix it, and leaving it would select it again forever.
            log.error("Recurring job {} has an invalid schedule (cron '{}', zone '{}'); marking it FAILED",
                    schedule.jobId(), schedule.cronExpression(), schedule.timezone(), e);
            jobRepository.markFailed(schedule.jobId());
            return 0;
        }

        // Runs strictly after `after`: everything up to generated_until exists already; a run
        // exactly at starts_at is kept (minusNanos); and runs missed while the generator was
        // down (generated_until in the past) are skipped by starting from now.
        OffsetDateTime after = latest(schedule.generatedUntil(), schedule.startsAt().minusNanos(1), now);
        OffsetDateTime until = schedule.endsAt() != null && schedule.endsAt().isBefore(horizon)
                ? schedule.endsAt()
                : horizon;
        int limit = schedule.maxOccurrences() != null
                ? schedule.maxOccurrences() - schedule.occurrencesGenerated()
                : Integer.MAX_VALUE;

        List<OffsetDateTime> runTimes = Occurrences.between(cron, zone, after, until, limit);
        int inserted = executionRepository.insertAll(schedule.jobId(), runTimes);
        scheduleRepository.advance(schedule.jobId(), inserted, horizon);
        return inserted;
    }

    private static OffsetDateTime latest(OffsetDateTime first, OffsetDateTime... others) {
        OffsetDateTime latest = first;
        for (OffsetDateTime other : others) {
            if (other.isAfter(latest)) {
                latest = other;
            }
        }
        return latest;
    }

    private record BatchResult(int schedules, int created) {
    }
}
