package com.ambitiousconcepts.opsatlas.shared;

/**
 * An {@code If-Match} precondition was missing or did not hold.
 *
 * <p>CLAUDE.md section 9 requires optimistic locking wherever concurrent updates
 * are plausible. Two outcomes, deliberately different:
 *
 * <ul>
 *   <li>{@link Kind#REQUIRED} - 428. No {@code If-Match} was sent. The update is
 *       refused rather than applied blindly, because a client that does not say
 *       which version it read cannot be overwriting an intended one.
 *   <li>{@link Kind#FAILED} - 412. An {@code If-Match} was sent and does not
 *       match. Someone else changed the resource since it was read.
 * </ul>
 */
public class PreconditionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        REQUIRED,
        FAILED
    }

    private final Kind kind;

    public PreconditionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
