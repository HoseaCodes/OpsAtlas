package com.ambitiousconcepts.opsatlas.shared;

import java.util.List;

/** A submitted document was structurally or semantically invalid. Carries every violation found, not just the first. */
public class ValidationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    public ValidationFailedException(String summary, List<Violation> violations) {
        super(summary);
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("a validation failure with no violations tells the caller nothing");
        }
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
