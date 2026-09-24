package com.jobscheduler.common.schedule;

import org.springframework.scheduling.support.CronExpression;

// Clients send standard 5-field cron (minute hour day-of-month month day-of-week).
// Spring's CronExpression starts with a seconds field; we fix it at 0, which also
// makes once a minute the highest possible frequency.
public final class Cron {

    private static final int FIELDS = 5;

    private Cron() {
    }

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("must not be blank");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != FIELDS) {
            throw new IllegalArgumentException("must have 5 fields (minute hour day-of-month month day-of-week), found "
                    + fields.length);
        }
        try {
            return CronExpression.parse("0 " + String.join(" ", fields));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(withoutSpringSuffix(e.getMessage()), e);
        }
    }

    // Spring appends the 6-field expression (with our "0 " prefix), which would confuse the client.
    private static String withoutSpringSuffix(String message) {
        int suffix = message.indexOf(" in cron expression");
        return suffix < 0 ? message : message.substring(0, suffix);
    }
}