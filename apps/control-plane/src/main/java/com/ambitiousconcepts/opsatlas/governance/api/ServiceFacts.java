package com.ambitiousconcepts.opsatlas.governance.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything governance knows about a service when it scores one.
 *
 * <p>This is the extension point ADR 0004 describes, and it is deliberately
 * defined in governance's own terms rather than being catalog's manifest type
 * passed through. Two reasons:
 *
 * <ul>
 *   <li>governance does not depend on catalog. The dependency runs one way -
 *       catalog calls governance when it registers something - and a shared
 *       manifest type would make it circular in everything but the compiler.
 *   <li>when the observer lands, governance needs facts that are not in any
 *       manifest: whether traces arrived, whether the probe answered, when it
 *       was last seen. Those become fields here, and no existing rule's
 *       signature changes.
 * </ul>
 *
 * <p>{@link Optional} is used throughout because absence is the thing most rules
 * are about. An empty list and an absent list are also different: "I have no
 * dependencies" is a statement, "I have not said" is a gap, and
 * {@code dependencies-declared} is the rule that tells them apart.
 */
public record ServiceFacts(
        UUID serviceId,
        String slug,
        int tier,
        Optional<String> owner,
        List<EnvironmentFacts> environments,
        Optional<String> readinessPath,
        Optional<String> livenessPath,
        Optional<String> observabilityServiceName,
        Optional<String> runbook,
        Optional<Slo> slo,
        Optional<List<String>> journeys,
        Optional<List<String>> dependencies) {

    /** @param url absent when the manifest declares no URL for this environment */
    public record EnvironmentFacts(String name, Optional<String> url) {}

    public record Slo(double availability, String window) {}

    /**
     * Tier 1 and 2 carry obligations tier 3 does not. Rules ask this rather than
     * comparing tier numbers themselves, so the threshold lives in one place.
     */
    public boolean isProductionCritical() {
        return tier <= 2;
    }
}
