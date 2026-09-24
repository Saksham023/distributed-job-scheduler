package com.jobscheduler.migrations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// One-shot job: Flyway applies pending migrations during startup, then the
// process exits. A failed migration fails startup, so the exit code is non-zero.
@SpringBootApplication
public class DbMigrationsApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(DbMigrationsApplication.class, args)));
    }
}
