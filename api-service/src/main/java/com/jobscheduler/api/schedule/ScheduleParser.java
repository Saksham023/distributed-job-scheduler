package com.jobscheduler.api.schedule;

import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.exception.FieldViolation;
import com.jobscheduler.api.exception.RequestValidationException;
import com.jobscheduler.common.schedule.Cron;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Checks the rules that span several fields and turns the request into a Schedule.
// Single-field rules (@Future, @Positive) are already checked on the DTO.
@Component
public class ScheduleParser {

    // Named zones only (e.g. Asia/Kolkata); fixed offsets like +05:30 have no daylight-saving rules.
    private static final Set<String> ZONE_IDS = ZoneId.getAvailableZoneIds();

    public Schedule parse(CreateJobRequest request) {
        boolean oneTime = request.scheduledAt() != null;
        boolean recurring = request.cronExpression() != null;
        if (oneTime && recurring) {
            throw invalid("cronExpression", "must not be combined with scheduledAt");
        }
        if (!oneTime && !recurring) {
            throw invalid("scheduledAt", "either scheduledAt (one-time) or cronExpression (recurring) is required");
        }
        return oneTime ? parseOneTime(request) : parseRecurring(request);
    }

    private Schedule parseOneTime(CreateJobRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        rejectIfPresent(violations, "timezone", request.timezone());
        rejectIfPresent(violations, "startsAt", request.startsAt());
        rejectIfPresent(violations, "endsAt", request.endsAt());
        rejectIfPresent(violations, "maxOccurrences", request.maxOccurrences());
        throwIfAny(violations);
        return new Schedule.OneTime(request.scheduledAt());
    }

    private Schedule parseRecurring(CreateJobRequest request) {
        List<FieldViolation> violations = new ArrayList<>();

        CronExpression cron = null;
        try {
            cron = Cron.parse(request.cronExpression());
        } catch (IllegalArgumentException e) {
            violations.add(new FieldViolation("cronExpression", e.getMessage()));
        }

        ZoneId zone = null;
        if (request.timezone() == null || request.timezone().isBlank()) {
            violations.add(new FieldViolation("timezone", "is required with cronExpression"));
        } else if (!ZONE_IDS.contains(request.timezone())) {
            violations.add(new FieldViolation("timezone", "unknown time zone; use an IANA name such as Asia/Kolkata"));
        } else {
            zone = ZoneId.of(request.timezone());
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime startsAt = request.startsAt() != null ? request.startsAt() : now;
        OffsetDateTime endsAt = request.endsAt();
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            violations.add(new FieldViolation("endsAt", "must be after startsAt"));
        }

        // Some expressions parse but never run, e.g. "0 9 31 2 *" (31 February).
        if (violations.isEmpty()) {
            OffsetDateTime from = startsAt.isAfter(now) ? startsAt : now;
            ZonedDateTime firstRun = cron.next(from.atZoneSameInstant(zone));
            if (firstRun == null || (endsAt != null && firstRun.toOffsetDateTime().isAfter(endsAt))) {
                violations.add(new FieldViolation("cronExpression", "never runs between startsAt and endsAt"));
            }
        }

        throwIfAny(violations);
        return new Schedule.Recurring(request.cronExpression().trim(), cron, zone, startsAt, endsAt,
                request.maxOccurrences());
    }

    private static void rejectIfPresent(List<FieldViolation> violations, String field, Object value) {
        if (value != null) {
            violations.add(new FieldViolation(field, "is only allowed with cronExpression"));
        }
    }

    private static void throwIfAny(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
    }

    private static RequestValidationException invalid(String field, String message) {
        return new RequestValidationException(List.of(new FieldViolation(field, message)));
    }
}