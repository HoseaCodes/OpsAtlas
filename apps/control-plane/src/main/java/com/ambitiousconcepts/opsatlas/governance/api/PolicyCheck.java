package com.ambitiousconcepts.opsatlas.governance.api;

/**
 * One production-readiness rule.
 *
 * <p>Implementations are Spring beans; {@code PolicyCatalog} collects them.
 * Adding a rule is one class and one test, and requires no change to anything
 * that evaluates or stores results. ADR 0004.
 *
 * <p><strong>Every rule in slice one reads only what a manifest declares.</strong>
 * Nothing here verifies that a runbook URL resolves, that traces arrive, or that
 * a probe answers - the integrations that could check those do not exist. The
 * console says "declaration checks" for exactly this reason, and calling them
 * production-readiness checks would be the false claim CLAUDE.md section 3
 * rule 1 forbids.
 */
public interface PolicyCheck {

    /**
     * Stable identifier, stored on every result. Changing one orphans the
     * history of that rule, so it is effectively permanent once shipped.
     */
    String id();

    /** Short human-facing name, used as the scorecard matrix column header. */
    String title();

    /** One sentence on what the rule is for, shown when a reader asks why it exists. */
    String rationale();

    /** Whether this rule applies to this service at all. */
    Applicability appliesTo(ServiceFacts facts);

    /** Only called when {@link #appliesTo} returned {@link Applicability#REQUIRED}. */
    CheckOutcome evaluate(ServiceFacts facts);
}
