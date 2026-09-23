package com.jobscheduler.job_scheduler_service.repository;

import com.jobscheduler.job_scheduler_service.model.TaskTypeTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TaskTypeRepository {

    private final JdbcClient jdbcClient;

    public TaskTypeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<TaskTypeTemplate> findWithActiveTemplate(String name) {
        return jdbcClient.sql("""
                        SELECT tt.id AS task_type_id,
                               t.id AS template_id,
                               t.params_schema
                        FROM task_types tt
                        LEFT JOIN templates t
                               ON t.task_type_id = tt.id AND t.is_active
                        WHERE tt.name = :name
                        """)
                .param("name", name)
                .query(TaskTypeTemplate.class)
                .optional();
    }
}