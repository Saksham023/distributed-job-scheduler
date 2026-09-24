package com.jobscheduler.api.exception;

import java.util.List;

public class ParamsValidationException extends RuntimeException {

    private final List<FieldViolation> violations;

    public ParamsValidationException(List<FieldViolation> violations) {
        super("Invalid params: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> getViolations() {
        return violations;
    }
}