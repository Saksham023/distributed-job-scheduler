package com.jobscheduler.job_scheduler_service.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TaskTypeRepository {

    private final JdbcClient jdbcClient;

    public TaskTypeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Integer> findIdByName(String name) {
        return jdbcClient.sql("SELECT id FROM task_types WHERE name = :name")
                .param("name", name)
                .query(Integer.class)
                .optional();
    }
}