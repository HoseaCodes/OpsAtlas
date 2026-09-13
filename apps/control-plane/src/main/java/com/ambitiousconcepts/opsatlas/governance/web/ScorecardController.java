package com.ambitiousconcepts.opsatlas.governance.web;

import com.ambitiousconcepts.opsatlas.catalog.api.ServiceCatalog;
import com.ambitiousconcepts.opsatlas.governance.api.PolicyRuleView;
import com.ambitiousconcepts.opsatlas.governance.api.Scorecard;
import com.ambitiousconcepts.opsatlas.governance.api.ScorecardService;
import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scorecards.
 *
 * <p>Every rule served here reads only what a manifest declares. Nothing
 * verifies that a runbook link resolves, that traces arrive, or that a probe
 * answers - and {@code declarationOnly} on each rule says so, so the console
 * cannot present these as production-readiness checks without contradicting its
 * own data.
 */
@RestController
@RequestMapping("/api/v1")
class ScorecardController {

    private final ScorecardService scorecards;
    private final ServiceCatalog catalog;
    private final CurrentPrincipal currentPrincipal;

    ScorecardController(ScorecardService scorecards, ServiceCatalog catalog, CurrentPrincipal currentPrincipal) {
        this.scorecards = scorecards;
        this.catalog = catalog;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/services/{slug}/scorecard")
    Scorecard forService(@PathVariable String slug) {
        UUID orgId = currentPrincipal.get().orgId();

        // Resolved through the catalog's public API rather than by querying its
        // tables. That is also what makes an unknown slug a 404 here for the
        // same reason it is a 404 there.
        UUID serviceId = catalog.get(orgId, slug).id();

        return scorecards
                .latestFor(orgId, serviceId)
                // Registration always writes a scorecard in the same
                // transaction, so a registered service without one means that
                // invariant is broken - worth a loud 404 rather than an empty
                // object that looks like "not scored yet".
                .orElseThrow(() -> new NotFoundException("Scorecard for service", slug));
    }

    /** The rule set itself, so the console can render matrix columns and explain them. */
    @GetMapping("/policy/rules")
    PolicyRules rules() {
        List<PolicyRuleView> views = scorecards.rules().stream()
                .map(rule -> new PolicyRuleView(rule.id(), rule.title(), rule.rationale(), true))
                .toList();
        return new PolicyRules(scorecards.policySetVersion(), views);
    }

    /**
     * @param declarationOnlyNotice plain text the console is expected to show
     *     beside the scorecard. It is in the payload rather than hardcoded in the
     *     frontend so that it cannot drift from what the rules actually do.
     */
    record PolicyRules(String policySetVersion, List<PolicyRuleView> rules, String declarationOnlyNotice) {
        PolicyRules(String policySetVersion, List<PolicyRuleView> rules) {
            this(
                    policySetVersion,
                    rules,
                    "These checks read what a service.yaml declares. They do not verify that a runbook link"
                            + " resolves, that telemetry arrives, or that a health endpoint answers. Runtime"
                            + " verification arrives with the observer.");
        }
    }
}
