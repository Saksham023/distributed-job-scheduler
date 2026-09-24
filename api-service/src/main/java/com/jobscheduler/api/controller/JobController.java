package com.jobscheduler.api.controller;

import com.jobscheduler.api.dto.CreateJobRequest;
import com.jobscheduler.api.dto.ExecutionPage;
import com.jobscheduler.api.dto.JobResponse;
import com.jobscheduler.api.service.JobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(@Valid @RequestBody CreateJobRequest request) {
        JobResponse job = jobService.createJob(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(job.id())
                .toUri();
        return ResponseEntity.created(location).body(job);
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable UUID id) {
        return jobService.getJob(id);
    }

    // Runs of a job, newest first, `limit` per page; pass the previous page's nextBefore
    // as `before` to continue. Timestamps are ISO-8601 (URL-encode a '+' offset as %2B).
    @GetMapping("/{id}/executions")
    public ExecutionPage getExecutions(@PathVariable UUID id,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return jobService.getExecutions(id, before, limit);
    }
}