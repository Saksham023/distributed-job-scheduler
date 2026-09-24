package com.jobscheduler.watcher.repository;

import com.jobscheduler.common.model.JobExecution;
import com.jobscheduler.common.model.JobExecutionStatus;
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

    // Must run inside a transaction: the row locks are released when it commits.
    public List<JobExecution> lockDueForPublishing(OffsetDateTime dueBefore, int limit) {
        // 'PENDING' is a literal, not a bound parameter, so Postgres can use the
        // partial index idx_executions_pending_schedule (WHERE status = 'PENDING').
        return jdbcClient.sql("""
                        SELECT id, job_id, scheduled_at, status, attempt, error_message,
                               started_at, finished_at, created_at
                        FROM job_executions
                        WHERE status = 'PENDING' AND scheduled_at <= :dueBefore
                        ORDER BY scheduled_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("dueBefore", dueBefore)
                .param("limit", limit)
                .query(JobExecution.class)
                .list();
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
}
