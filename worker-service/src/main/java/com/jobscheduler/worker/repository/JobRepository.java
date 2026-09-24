package com.jobscheduler.worker.repository;

import com.jobscheduler.common.model.JobStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JobRepository {

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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