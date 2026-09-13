package com.ambitiousconcepts.opsatlas.governance.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A service's score at one moment, by one version of the rules.
 *
 * @param policySetVersion the rule set that produced this, so an old score can
 *     still be read as what it meant when it was computed
 * @param checksPassed how many applicable checks passed
 * @param checksApplicable how many applied - not the number of rules that exist
 */
public record Scorecard(
        UUID serviceId,
        String policySetVersion,
        Instant evaluatedAt,
        int checksPassed,
        int checksApplicable,
        List<CheckResult> checks) {

    /**
     * @param status PASS, FAIL or NOT_APPLICABLE
     * @param detail why it failed; null when it passed or does not apply
     */
    public record CheckResult(String checkId, String title, String status, @Schema(nullable = true) String detail) {}

    /**
     * Percentage of applicable checks passed, 0-100.
     *
     * <p>Returns 100 when nothing applies. A service with no obligations has met
     * all of them; reporting 0 would make an internal tool look like the worst
     * thing in the fleet.
     */
    public int percentage() {
        return checksApplicable == 0 ? 100 : Math.round(checksPassed * 100f / checksApplicable);
    }
}
