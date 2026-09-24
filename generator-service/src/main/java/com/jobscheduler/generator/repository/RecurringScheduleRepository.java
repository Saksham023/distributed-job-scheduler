package com.jobscheduler.generator.repository;

import com.jobscheduler.common.model.JobExecutionStatus;
import com.jobscheduler.common.model.JobStatus;
import com.jobscheduler.generator.model.DueSchedule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class RecurringScheduleRepository {

    private static final List<String> UNFINISHED = List.of(
            JobExecutionStatus.PENDING.name(), JobExecutionStatus.QUEUED.name(), JobExecutionStatus.PROCESSING.name());

    private final JdbcClient jdbcClient;

    public RecurringScheduleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Must run inside a transaction: the row locks are released when it commits.
    // Active schedules that still produce runs and have fewer than `refillBefore` of them created.
    // SKIP LOCKED lets several generators share the work without waiting on each other.
    public List<DueSchedule> lockDueForTopUp(OffsetDateTime refillBefore, int limit) {
        return jdbcClient.sql("""
                        SELECT rs.job_id, rs.cron_expression, rs.timezone, rs.starts_at, rs.ends_at,
                               rs.max_occurrences, rs.occurrences_generated, rs.generated_until
                        FROM recurring_schedules rs
                        JOIN jobs j ON j.id = rs.job_id
                        WHERE j.status = :active
                          AND rs.generated_until < :refillBefore
                          AND (rs.max_occurrences IS NULL OR rs.occurrences_generated < rs.max_occurrences)
                          AND (rs.ends_at IS NULL OR rs.generated_until < rs.ends_at)
                        ORDER BY rs.generated_until
                        LIMIT :limit
                        FOR UPDATE OF rs SKIP LOCKED
                        """)
                .param("active", JobStatus.ACTIVE.name())
                .param("refillBefore", refillBefore)
                .param("limit", limit)
                .query(DueSchedule.class)
                .list();
    }

    // GREATEST: the watermark only ever moves forward, even if two runs overlap.
    public void advance(UUID jobId, int inserted, OffsetDateTime generatedUntil) {
        jdbcClient.sql("""
                        UPDATE recurring_schedules
                        SET occurrences_generated = occurrences_generated + :inserted,
                            generated_until = GREATEST(generated_until, :generatedUntil)
                        WHERE job_id = :jobId
                        """)
                .param("jobId", jobId)
                .param("inserted", inserted)
                .param("generatedUntil", generatedUntil)
                .update();
    }

    // A recurring job is complete when its schedule can't produce more runs (max_occurrences
    // reached, or covered past ends_at) and none of its runs is still in progress. Failed runs
    // don't fail the job: each run's own status records that. Safe to repeat and to run from
    // several generators: only ACTIVE jobs are updated.
    public int completeFinishedJobs() {
        return jdbcClient.sql("""
                        UPDATE jobs j
                        SET status = :completed, updated_at = now()
                        FROM recurring_schedules rs
                        WHERE rs.job_id = j.id
                          AND j.status = :active
                          AND (   (rs.max_occurrences IS NOT NULL AND rs.occurrences_generated >= rs.max_occurrences)
                               OR (rs.ends_at IS NOT NULL AND rs.generated_until >= rs.ends_at))
                          AND NOT EXISTS (SELECT 1 FROM job_executions je
                                          WHERE je.job_id = j.id AND je.status IN (:unfinished))
                        """)
                .param("completed", JobStatus.COMPLETED.name())
                .param("active", JobStatus.ACTIVE.name())
                .param("unfinished", UNFINISHED)
                .update();
    }
}
