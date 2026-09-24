package com.jobscheduler.api.repository;

import com.jobscheduler.api.model.JobDetails;
import com.jobscheduler.api.schedule.Schedule;
import com.jobscheduler.common.model.JobExecutionStatus;
import com.jobscheduler.common.model.ScheduleType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private static final List<String> UNFINISHED = List.of(
            JobExecutionStatus.PENDING.name(), JobExecutionStatus.QUEUED.name(), JobExecutionStatus.PROCESSING.name());

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public UUID insert(long userId, int taskTypeId, int templateId, String paramsJson, ScheduleType scheduleType) {
        return jdbcClient.sql("""
                        INSERT INTO jobs (user_id, task_type_id, template_id, params, schedule_type)
                        VALUES (:userId, :taskTypeId, :templateId, CAST(:params AS jsonb), :scheduleType)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("taskTypeId", taskTypeId)
                .param("templateId", templateId)
                .param("params", paramsJson)
                .param("scheduleType", scheduleType.name())
                .query(UUID.class)
                .single();
    }

    public void insertOneTimeSchedule(UUID jobId, OffsetDateTime scheduledAt) {
        jdbcClient.sql("""
                        INSERT INTO one_time_schedules (job_id, scheduled_at)
                        VALUES (:jobId, :scheduledAt)
                        """)
                .param("jobId", jobId)
                .param("scheduledAt", scheduledAt)
                .update();
    }

    public void insertRecurringSchedule(UUID jobId, Schedule.Recurring schedule,
                                        int occurrencesGenerated, OffsetDateTime generatedUntil) {
        jdbcClient.sql("""
                        INSERT INTO recurring_schedules (job_id, cron_expression, timezone, starts_at, ends_at,
                                                         max_occurrences, occurrences_generated, generated_until)
                        VALUES (:jobId, :cronExpression, :timezone, :startsAt, :endsAt,
                                :maxOccurrences, :occurrencesGenerated, :generatedUntil)
                        """)
                .param("jobId", jobId)
                .param("cronExpression", schedule.cronExpression())
                .param("timezone", schedule.zone().getId())
                .param("startsAt", schedule.startsAt())
                .param("endsAt", schedule.endsAt())
                .param("maxOccurrences", schedule.maxOccurrences())
                .param("occurrencesGenerated", occurrencesGenerated)
                .param("generatedUntil", generatedUntil)
                .update();
    }

    public Optional<JobDetails> findDetailsById(UUID id) {
        return jdbcClient.sql("""
                        SELECT j.id, j.user_id, tt.name AS task_type, j.params,
                               j.schedule_type, j.status,
                               je.scheduled_at, je.status AS execution_status,
                               j.created_at,
                               rs.cron_expression, rs.timezone, rs.starts_at, rs.ends_at,
                               rs.max_occurrences, rs.occurrences_generated
                        FROM jobs j
                        JOIN task_types tt ON tt.id = j.task_type_id
                        LEFT JOIN recurring_schedules rs ON rs.job_id = j.id
                        -- The run to show: the earliest unfinished one, else the most recent.
                        LEFT JOIN LATERAL (
                            SELECT scheduled_at, status
                            FROM job_executions
                            WHERE job_id = j.id
                            ORDER BY (status IN (:unfinished)) DESC,
                                     CASE WHEN status IN (:unfinished) THEN scheduled_at END ASC,
                                     scheduled_at DESC
                            LIMIT 1
                        ) je ON true
                        WHERE j.id = :id
                        """)
                .param("id", id)
                .param("unfinished", UNFINISHED)
                .query(JobDetails.class)
                .optional();
    }

    public boolean exists(UUID id) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM jobs WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }
}
