package com.ambitiousconcepts.opsatlas.governance.api;

/**
 * The result of evaluating one rule against one service.
 *
 * <p>A failure must carry a reason, and the constructor enforces it. A scorecard
 * that says "6 of 10" without saying which four and why is a number, not a tool:
 * the owner still has to go and find out what is wrong. The database refuses a
 * detail-less failure too, so this cannot be worked around by accident.
 */
public record CheckOutcome(Status status, String detail) {

    public enum Status {
        PASS,
        FAIL,
        NOT_APPLICABLE
    }

    public CheckOutcome {
        if (status == Status.FAIL && (detail == null || detail.isBlank())) {
            throw new IllegalArgumentException(
                    "A failing check must say why. A score with no reason is a number, not something to act on.");
        }
    }

    public static CheckOutcome pass() {
        return new CheckOutcome(Status.PASS, null);
    }

    /** @param detail a complete sentence the owning team can act on */
    public static CheckOutcome fail(String detail) {
        return new CheckOutcome(Status.FAIL, detail);
    }

    public static CheckOutcome notApplicable() {
        return new CheckOutcome(Status.NOT_APPLICABLE, null);
    }

    public boolean passed() {
        return status == Status.PASS;
    }

    public boolean applicable() {
        return status != Status.NOT_APPLICABLE;
    }
}
