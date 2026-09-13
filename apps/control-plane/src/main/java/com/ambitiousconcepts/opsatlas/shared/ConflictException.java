package com.ambitiousconcepts.opsatlas.shared;

/**
 * The request cannot be applied to the resource in its current state.
 *
 * <p>Distinct from a validation failure: the document is fine, but what it asks
 * for conflicts with something already stored. The message must say what to do
 * instead, because a 409 with no route forward is a dead end.
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Violation violation;

    public ConflictException(String message, Violation violation) {
        super(message);
        this.violation = violation;
    }

    public ConflictException(String message) {
        this(message, null);
    }

    /** The specific field in conflict, where there is one. */
    public java.util.Optional<Violation> violation() {
        return java.util.Optional.ofNullable(violation);
    }
}
