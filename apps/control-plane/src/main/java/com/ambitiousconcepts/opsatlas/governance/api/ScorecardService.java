package com.ambitiousconcepts.opsatlas.governance.api;

import java.util.Optional;
import java.util.UUID;

/** Evaluating and reading scorecards. The governance module's public surface. */
public interface ScorecardService {

    /**
     * Evaluate every rule against a service and store the result.
     *
     * <p>Called by {@code catalog} inside the registration transaction, so that
     * a service row and its first scorecard either both exist or neither does
     * (CLAUDE.md section 8).
     */
    Scorecard evaluateAndStore(UUID orgId, ServiceFacts facts);

    /** The most recent scorecard for a service, or empty if it has never been evaluated. */
    Optional<Scorecard> latestFor(UUID orgId, UUID serviceId);

    /** Every rule currently in force, so the console can render the matrix columns. */
    java.util.List<PolicyCheck> rules();

    /** The version identifying the current rule set. */
    String policySetVersion();
}
