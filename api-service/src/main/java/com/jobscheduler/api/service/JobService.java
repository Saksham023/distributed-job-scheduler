package com.jobscheduler.api.service;

import com.jobscheduler.api.config.FastPathProperties;
import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.api.exception.FieldViolation;
import com.jobscheduler.api.exception.InvalidRequestException;
import com.jobscheduler.api.exception.ParamsValidationException;
import com.jobscheduler.api.exception.ResourceNotFoundException;
import com.jobscheduler.common.messaging.JobExecutionPublisher;
import com.jobscheduler.api.model.JobDetails;
import com.jobscheduler.common.model.ScheduleType;
import com.jobscheduler.api.model.TaskTypeTemplate;
import com.jobscheduler.api.repository.JobExecutionRepository;
import com.jobscheduler.api.repository.JobRepository;
import com.jobscheduler.api.repository.TaskTypeRepository;
import com.jobscheduler.api.template.ParamsValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

    public JobService(JobRepository jobRepository,
                      JobExecutionRepository jobExecutionRepository,
                      TaskTypeRepository taskTypeRepository,
                      JobExecutionPublisher publisher,
                      TransactionTemplate transactionTemplate,
                      JsonMapper jsonMapper,
                      FastPathProperties fastPathProperties,
                      ParamsValidator paramsValidator) {
        this.jobRepository = jobRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.taskTypeRepository = taskTypeRepository;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.jsonMapper = jsonMapper;
        this.fastPathProperties = fastPathProperties;
        this.paramsValidator = paramsValidator;
    }

    public JobResponse createJob(CreateJobRequest request) {
        TaskTypeTemplate taskType = taskTypeRepository.findWithActiveTemplate(request.taskType())
                .orElseThrow(() -> new InvalidRequestException("Unknown task type: " + request.taskType()));
        if (taskType.templateId() == null) {
            throw new IllegalStateException("No active template for task type: " + request.taskType());
        }

        List<FieldViolation> violations =
                paramsValidator.validate(taskType.templateId(), taskType.paramsSchema(), request.params());
        if (!violations.isEmpty()) {
            throw new ParamsValidationException(violations);
        }

        String paramsJson = jsonMapper.writeValueAsString(request.params());

        CreatedJob created = transactionTemplate.execute(status -> {
            UUID jobId = jobRepository.insert(request.userId(), taskType.taskTypeId(), taskType.templateId(),
                    paramsJson, ScheduleType.ONE_TIME);
            jobRepository.insertOneTimeSchedule(jobId, request.scheduledAt());
            UUID executionId = jobExecutionRepository.insert(jobId, request.scheduledAt());
            return new CreatedJob(jobId, executionId);
        });

        if (isDueSoon(request.scheduledAt())) {
            publishFastPath(created.executionId(), request.scheduledAt());
        }

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
                job.createdAt());
    }

    private boolean isDueSoon(OffsetDateTime scheduledAt) {
        return !scheduledAt.isAfter(OffsetDateTime.now().plus(fastPathProperties.lookahead()));
    }

    private void publishFastPath(UUID executionId, OffsetDateTime scheduledAt) {
        try {
            publisher.publish(executionId, scheduledAt);
            jobExecutionRepository.markQueued(executionId);
        } catch (RuntimeException e) {
            // Best-effort: the execution stays PENDING and the watcher enqueues it on its next run.
            log.warn("Fast-path publish failed for execution {}; leaving it for the watcher", executionId, e);
        }
    }

    private record CreatedJob(UUID jobId, UUID executionId) {}
}