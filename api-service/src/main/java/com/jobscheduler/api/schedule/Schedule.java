package com.jobscheduler.api.schedule;

import com.jobscheduler.common.model.ScheduleType;
import org.springframework.scheduling.support.CronExpression;

import java.time.OffsetDateTime;
import java.time.ZoneId;

// A validated schedule: exactly one of these two shapes.
public sealed interface Schedule {

    // The label stored in jobs.schedule_type.
    ScheduleType type();

    record OneTime(OffsetDateTime scheduledAt) implements Schedule {

        @Override
        public ScheduleType type() {
            return ScheduleType.ONE_TIME;
        }
    }

    record Recurring(
            String cronExpression,      // as the client sent it (5 fields); this is what's stored
            CronExpression cron,        // parsed, for computing run times
            ZoneId zone,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,      // null = no end
            Integer maxOccurrences      // null = no limit
    ) implements Schedule {

        @Override
        public ScheduleType type() {
            return ScheduleType.RECURRING;
        }
    }
}