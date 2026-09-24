package com.jobscheduler.generator.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class JobExecutionRepository {

    private final JdbcClient jdbcClient;

    public JobExecutionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // One statement for any number of rows (the list expands to one placeholder per time).
    // ON CONFLICT: a run that already exists is skipped, so the result is the number of rows
    // actually created, which is what occurrences_generated must count.
    public int insertAll(UUID jobId, List<OffsetDateTime> scheduledTimes) {
        if (scheduledTimes.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql("""
                        INSERT INTO job_executions (job_id, scheduled_at)
                        SELECT :jobId, t FROM unnest(ARRAY[:times]::timestamptz[]) AS t
                        ON CONFLICT (job_id, scheduled_at) DO NOTHING
                        """)
                .param("jobId", jobId)
                .param("times", scheduledTimes)
                .update();
    }
}
