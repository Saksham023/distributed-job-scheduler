package com.jobscheduler.generator.repository;

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
