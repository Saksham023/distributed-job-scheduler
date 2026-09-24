package com.jobscheduler.api.repository;

import com.jobscheduler.common.model.JobExecutionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class JobExecutionRepository {

    private final JdbcClient jdbcClient;

    public JobExecutionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public UUID insert(UUID jobId, OffsetDateTime scheduledAt) {
        return jdbcClient.sql("""
                        INSERT INTO job_executions (job_id, scheduled_at)
                        VALUES (:jobId, :scheduledAt)
                        RETURNING id
                        """)
                .param("jobId", jobId)
                .param("scheduledAt", scheduledAt)
                .query(UUID.class)
                .single();
    }

    public boolean markQueued(UUID id) {
        int updated = jdbcClient.sql("""
                        UPDATE job_executions
                        SET status = :queued
                        WHERE id = :id AND status = :pending
                        """)
                .param("id", id)
                .param("queued", JobExecutionStatus.QUEUED.name())
                .param("pending", JobExecutionStatus.PENDING.name())
                .update();
        return updated == 1;
    }
}
