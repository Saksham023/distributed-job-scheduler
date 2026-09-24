package com.jobscheduler.common.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public final class Occurrences {

    private Occurrences() {
    }

    // Run times strictly after `after` and up to and including `until`, at most `limit` of them.
    // Computed in the job's zone, so "09:00" stays 09:00 local across daylight-saving changes.
    public static List<OffsetDateTime> between(CronExpression cron, ZoneId zone,
                                               OffsetDateTime after, OffsetDateTime until, int limit) {
        List<OffsetDateTime> runTimes = new ArrayList<>();
        ZonedDateTime next = cron.next(after.atZoneSameInstant(zone));
        while (next != null && runTimes.size() < limit && !next.toOffsetDateTime().isAfter(until)) {
            runTimes.add(next.toOffsetDateTime());
            next = cron.next(next);
        }
        return runTimes;
    }
}