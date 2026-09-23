package com.jobscheduler.job_scheduler_service.repository;

import com.jobscheduler.job_scheduler_service.model.JobDetails;
import com.jobscheduler.job_scheduler_service.model.JobStatus;
import com.jobscheduler.job_scheduler_service.model.ScheduleType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public UUID insert(long userId, int taskTypeId, int templateId, String paramsJson, ScheduleType scheduleType) {
        return jdbcClient.sql("""
                        INSERT INTO jobs (user_id, task_type_id, template_id, params, schedule_type)
                        VALUES (:userId, :taskTypeId, :templateId, CAST(:params AS jsonb), :scheduleType)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("taskTypeId", taskTypeId)
                .param("templateId", templateId)
                .param("params", paramsJson)
                .param("scheduleType", scheduleType.name())
                .query(UUID.class)
                .single();
    }

    public void insertOneTimeSchedule(UUID jobId, OffsetDateTime scheduledAt) {
        jdbcClient.sql("""
                        INSERT INTO one_time_schedules (job_id, scheduled_at)
                        VALUES (:jobId, :scheduledAt)
                        """)
                .param("jobId", jobId)
                .param("scheduledAt", scheduledAt)
                .update();
    }

    public Optional<JobDetails> findDetailsById(UUID id) {
        return jdbcClient.sql("""
                        SELECT j.id, j.user_id, tt.name AS task_type, j.params,
                               j.schedule_type, j.status,
                               je.scheduled_at, je.status AS execution_status,
                               j.created_at
                        FROM jobs j
                        JOIN task_types tt ON tt.id = j.task_type_id
                        LEFT JOIN LATERAL (
                            SELECT scheduled_at, status
                            FROM job_executions
                            WHERE job_id = j.id
                            ORDER BY scheduled_at DESC
                            LIMIT 1
                        ) je ON true
                        WHERE j.id = :id
                        """)
                .param("id", id)
                .query(JobDetails.class)
                .optional();
    }

    public void markCompleted(UUID id) {
        jdbcClient.sql("UPDATE jobs SET status = :completed, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("completed", JobStatus.COMPLETED.name())
                .update();
    }

    public void markFailed(UUID id) {
        jdbcClient.sql("""
                        UPDATE jobs SET status = :failed, updated_at = now()
                        WHERE id = :id AND status = :active
                        """)
                .param("id", id)
                .param("failed", JobStatus.FAILED.name())
                .param("active", JobStatus.ACTIVE.name())
                .update();
    }
}