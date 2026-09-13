package com.ambitiousconcepts.opsatlas.governance.internal;

import com.ambitiousconcepts.opsatlas.governance.api.PolicyCheck;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Every rule currently in force.
 *
 * <p>Spring injects all {@link PolicyCheck} beans, so adding a rule is one class
 * and nothing else. They are ordered by id so the scorecard matrix columns are
 * stable across restarts.
 */
@Component
public class PolicyCatalog {

    /**
     * The version stamped on every evaluation.
     *
     * <p><strong>Bump this whenever a rule is added, removed, or changed in a way
     * that alters its verdict.</strong> It is what lets a score from last month
     * still be read as what it meant then, rather than being silently reinterpreted
     * under today's rules. ADR 0004.
     */
    public static final String VERSION = "2026-09-12.1";

    private final List<PolicyCheck> checks;

    PolicyCatalog(List<PolicyCheck> checks) {
        List<PolicyCheck> ordered =
                checks.stream().sorted(Comparator.comparing(PolicyCheck::id)).toList();

        List<String> duplicates = ordered.stream()
                .collect(Collectors.groupingBy(PolicyCheck::id, Collectors.counting()))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .map(java.util.Map.Entry::getKey)
                .toList();

        if (!duplicates.isEmpty()) {
            // Two rules sharing an id would collide on the policy_result_check
            // primary key, so one would silently overwrite the other's verdict.
            throw new IllegalStateException("Duplicate policy check ids: " + duplicates);
        }

        this.checks = ordered;
    }

    public List<PolicyCheck> all() {
        return checks;
    }

    public String version() {
        return VERSION;
    }
}
