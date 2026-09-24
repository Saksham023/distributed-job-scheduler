package com.jobscheduler.api.repository;

import com.jobscheduler.api.dto.ExecutionResponse;
import com.jobscheduler.common.model.JobExecutionStatus;
import com.jobscheduler.common.model.ScheduledExecution;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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

    public int markQueued(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql("""
                        UPDATE job_executions
                        SET status = :queued
                        WHERE id IN (:ids) AND status = :pending
                        """)
                .param("ids", ids)
                .param("queued", JobExecutionStatus.QUEUED.name())
                .param("pending", JobExecutionStatus.PENDING.name())
                .update();
    }

    // One statement for any number of rows: the list expands to one placeholder per time
    // (like the watcher's IN (:ids)), and unnest turns the array into rows.
    public List<ScheduledExecution> insertAll(UUID jobId, List<OffsetDateTime> scheduledTimes) {
        if (scheduledTimes.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        INSERT INTO job_executions (job_id, scheduled_at)
                        SELECT :jobId, t FROM unnest(ARRAY[:times]::timestamptz[]) AS t
                        RETURNING id, scheduled_at
                        """)
                .param("jobId", jobId)
                .param("times", scheduledTimes)
                .query(ScheduledExecution.class)
                .list();
    }

    // Newest first. Keyset pagination: the next page starts strictly before the last
    // scheduled_at returned, which is unique per job, and the UNIQUE (job_id, scheduled_at)
    // index serves the query directly, however deep the client pages.
    public List<ExecutionResponse> findByJob(UUID jobId, OffsetDateTime before, int limit) {
        return jdbcClient.sql("""
                        SELECT id, scheduled_at, status, attempt, error_message, started_at, finished_at
                        FROM job_executions
                        WHERE job_id = :jobId
                          AND (CAST(:before AS timestamptz) IS NULL OR scheduled_at < :before)
                        ORDER BY scheduled_at DESC
                        LIMIT :limit
                        """)
                .param("jobId", jobId)
                .param("before", before)
                .param("limit", limit)
                .query(ExecutionResponse.class)
                .list();
    }
}
