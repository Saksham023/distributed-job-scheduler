package com.jobscheduler.api.service;

import com.jobscheduler.api.config.FastPathProperties;
import com.jobscheduler.api.config.RecurringProperties;
import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.ExecutionPage;
import com.jobscheduler.api.dto.ExecutionResponse;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.api.exception.FieldViolation;
import com.jobscheduler.api.exception.InvalidRequestException;
import com.jobscheduler.api.exception.RequestValidationException;
import com.jobscheduler.api.exception.ResourceNotFoundException;
import com.jobscheduler.api.schedule.Schedule;
import com.jobscheduler.api.schedule.ScheduleParser;
import com.jobscheduler.common.messaging.JobExecutionPublisher;
import com.jobscheduler.api.model.JobDetails;
import com.jobscheduler.api.model.TaskTypeTemplate;
import com.jobscheduler.api.repository.JobExecutionRepository;
import com.jobscheduler.api.repository.JobRepository;
import com.jobscheduler.api.repository.TaskTypeRepository;
import com.jobscheduler.api.template.ParamsValidator;
import com.jobscheduler.common.model.ScheduleType;
import com.jobscheduler.common.model.ScheduledExecution;
import com.jobscheduler.common.schedule.Occurrences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};

    private final JobRepository jobRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final TaskTypeRepository taskTypeRepository;
    private final JobExecutionPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final FastPathProperties fastPathProperties;
    private final ParamsValidator paramsValidator;
    private final ScheduleParser scheduleParser;
    private final RecurringProperties recurringProperties;
    public JobService(JobRepository jobRepository,
                      JobExecutionRepository jobExecutionRepository,
                      TaskTypeRepository taskTypeRepository,
                      JobExecutionPublisher publisher,
                      TransactionTemplate transactionTemplate,
                      JsonMapper jsonMapper,
                      FastPathProperties fastPathProperties,
                      ParamsValidator paramsValidator,
                      ScheduleParser scheduleParser,
                      RecurringProperties recurringProperties) {
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.taskTypeRepository = taskTypeRepository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.jsonMapper = jsonMapper;
        this.fastPathProperties = fastPathProperties;
        this.paramsValidator = paramsValidator;
        this.scheduleParser = scheduleParser;
        this.recurringProperties = recurringProperties;
    }

    public JobResponse createJob(CreateJobRequest request) {
        Schedule schedule = scheduleParser.parse(request);

        TaskTypeTemplate taskType = taskTypeRepository.findWithActiveTemplate(request.taskType())
                .orElseThrow(() -> new InvalidRequestException("Unknown task type: " + request.taskType()));
        if (taskType.templateId() == null) {
            throw new IllegalStateException("No active template for task type: " + request.taskType());
        }

        List<FieldViolation> violations =
                paramsValidator.validate(taskType.templateId(), taskType.paramsSchema(), request.params());
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }

        String paramsJson = jsonMapper.writeValueAsString(request.params());

        return switch (schedule) {
            case Schedule.OneTime oneTime -> createOneTime(request, taskType, paramsJson, oneTime);
            case Schedule.Recurring recurring -> createRecurring(request, taskType, paramsJson, recurring);
        };
    }

    private JobResponse createOneTime(CreateJobRequest request, TaskTypeTemplate taskType, String paramsJson,
                                      Schedule.OneTime schedule) {
        OffsetDateTime scheduledAt = schedule.scheduledAt();
        CreatedJob created = transactionTemplate.execute(status -> {
            UUID jobId = jobRepository.insert(request.userId(), taskType.taskTypeId(), taskType.templateId(),
                    paramsJson, schedule.type());
            jobRepository.insertOneTimeSchedule(jobId, scheduledAt);
            UUID executionId = jobExecutionRepository.insert(jobId, scheduledAt);
            return new CreatedJob(jobId, List.of(new ScheduledExecution(executionId, scheduledAt)));
        });

        publishDueSoon(created.executions());
        return getJob(created.jobId());
    }

    private JobResponse createRecurring(CreateJobRequest request, TaskTypeTemplate taskType, String paramsJson,
                                        Schedule.Recurring schedule) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime windowEnd = now.plus(recurringProperties.window());
        OffsetDateTime start = schedule.startsAt().isAfter(now) ? schedule.startsAt() : now;
        OffsetDateTime until = schedule.endsAt() != null && schedule.endsAt().isBefore(windowEnd)
                ? schedule.endsAt()
                : windowEnd;
        int limit = schedule.maxOccurrences() != null ? schedule.maxOccurrences() : Integer.MAX_VALUE;

        // Occurrences returns runs strictly after its start; minusNanos(1) keeps a run exactly at `start`.
        List<OffsetDateTime> runTimes = Occurrences.between(schedule.cron(), schedule.zone(),
                start.minusNanos(1), until, limit);

        CreatedJob created = transactionTemplate.execute(status -> {
            UUID jobId = jobRepository.insert(request.userId(), taskType.taskTypeId(), taskType.templateId(),
                    paramsJson, schedule.type());
            jobRepository.insertRecurringSchedule(jobId, schedule, runTimes.size(), windowEnd);
            return new CreatedJob(jobId, jobExecutionRepository.insertAll(jobId, runTimes));
        });

        publishDueSoon(created.executions());
        return getJob(created.jobId());
    }

    public JobResponse getJob(UUID jobId) {
        JobDetails job = jobRepository.findDetailsById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));

        return new JobResponse(
                job.id(),
                job.userId(),
                job.taskType(),
                jsonMapper.readValue(job.params(), PARAMS_TYPE),
                job.scheduleType(),
                job.status(),
                job.scheduledAt(),
                job.executionStatus(),
                recurrenceOf(job),
                job.createdAt());
    }

    public ExecutionPage getExecutions(UUID jobId, OffsetDateTime before, int limit) {
        if (!jobRepository.exists(jobId)) {
            throw new ResourceNotFoundException("Job not found: " + jobId);
        }
        // One row more than asked tells us whether another page exists, without a count query.
        List<ExecutionResponse> rows = jobExecutionRepository.findByJob(jobId, before, limit + 1);
        if (rows.size() <= limit) {
            return new ExecutionPage(rows, null);
        }
        List<ExecutionResponse> page = rows.subList(0, limit);
        return new ExecutionPage(page, page.getLast().scheduledAt());
    }

    private static JobResponse.Recurrence recurrenceOf(JobDetails job) {
        if (job.scheduleType() != ScheduleType.RECURRING) {
            return null;
        }
        return new JobResponse.Recurrence(job.cronExpression(), job.timezone(), job.startsAt(), job.endsAt(),
                job.maxOccurrences(), job.occurrencesGenerated());
    }

    // Fast path: publish what's due within the lookahead now, instead of waiting up to a
    // minute for the watcher. Best-effort: anything not published stays PENDING and the
    // watcher picks it up on its next run.
    private void publishDueSoon(List<ScheduledExecution> executions) {
        OffsetDateTime horizon = OffsetDateTime.now().plus(fastPathProperties.lookahead());
        List<ScheduledExecution> dueSoon = executions.stream()
                .filter(execution -> !execution.scheduledAt().isAfter(horizon))
                .toList();

        for (int from = 0; from < dueSoon.size(); from += JobExecutionPublisher.MAX_BATCH_SIZE) {
            List<ScheduledExecution> batch =
                    dueSoon.subList(from, Math.min(from + JobExecutionPublisher.MAX_BATCH_SIZE, dueSoon.size()));
            try {
                Set<UUID> accepted = publisher.publishBatch(batch);
                jobExecutionRepository.markQueued(accepted);
            } catch (RuntimeException e) {
                log.warn("Fast-path publish failed for {} executions; leaving them for the watcher", batch.size(), e);
            }
        }
    }

    private record CreatedJob(UUID jobId, List<ScheduledExecution> executions) {}
}