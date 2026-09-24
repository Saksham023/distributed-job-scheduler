package com.jobscheduler.worker.repository;

import com.jobscheduler.common.model.JobExecutionStatus;
import com.jobscheduler.worker.model.ClaimedExecution;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobExecutionRepository {

    private final JdbcClient jdbcClient;
    private static final List<String> CLAIMABLE =
            List.of(JobExecutionStatus.PENDING.name(), JobExecutionStatus.QUEUED.name());
    private static final List<String> IN_FLIGHT =
            List.of(JobExecutionStatus.PROCESSING.name(), JobExecutionStatus.QUEUED.name());

    public JobExecutionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<ClaimedExecution> claim(UUID id) {
        return jdbcClient.sql("""
                        UPDATE job_executions je
                        SET status = :processing, started_at = now(), attempt = je.attempt + 1
                        FROM jobs j
                        JOIN templates t ON t.id = j.template_id
                        WHERE je.id = :id
                          AND je.job_id = j.id
                          AND je.status IN (:claimable)
                        RETURNING je.id AS execution_id, j.id AS job_id, j.schedule_type,
                                  j.params, t.id AS template_id, t.subject, t.body
                        """)
                .param("id", id)
                .param("processing", JobExecutionStatus.PROCESSING.name())
                .param("claimable", CLAIMABLE)
                .query(ClaimedExecution.class)
                .optional();
    }

    public Optional<JobExecutionStatus> findStatus(UUID id) {
        return jdbcClient.sql("SELECT status FROM job_executions WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .map(JobExecutionStatus::valueOf);
    }

    public boolean resetProcessingToQueued(UUID id) {
        return jdbcClient.sql("""
                        UPDATE job_executions SET status = :queued
                        WHERE id = :id AND status = :processing
                        """)
                .param("id", id)
                .param("queued", JobExecutionStatus.QUEUED.name())
                .param("processing", JobExecutionStatus.PROCESSING.name())
                .update() == 1;
    }

    public void markCompleted(UUID id) {
        jdbcClient.sql("""
                        UPDATE job_executions SET status = :completed, finished_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("completed", JobExecutionStatus.COMPLETED.name())
                .update();
    }

    public boolean markForRetry(UUID id, String errorMessage) {
        return jdbcClient.sql("""
                        UPDATE job_executions SET status = :queued, error_message = :error
                        WHERE id = :id AND status IN (:inFlight)
                        """)
                .param("id", id)
                .param("queued", JobExecutionStatus.QUEUED.name())
                .param("error", errorMessage)
                .param("inFlight", IN_FLIGHT)
                .update() == 1;
    }

    public boolean markFailed(UUID id, String errorMessage) {
        return jdbcClient.sql("""
                        UPDATE job_executions SET status = :failed, finished_at = now(), error_message = :error
                        WHERE id = :id AND status IN (:inFlight)
                        """)
                .param("id", id)
                .param("failed", JobExecutionStatus.FAILED.name())
                .param("error", errorMessage)
                .param("inFlight", IN_FLIGHT)
                .update() == 1;
    }
}