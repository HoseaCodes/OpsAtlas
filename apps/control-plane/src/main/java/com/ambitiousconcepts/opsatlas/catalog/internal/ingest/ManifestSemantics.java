package com.ambitiousconcepts.opsatlas.catalog.internal.ingest;

import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Rules a manifest must satisfy that JSON Schema cannot express.
 *
 * <p>Deliberately small, and it will stay small: anything expressible in the
 * schema belongs in the schema, where the workspace checker and the control
 * plane both see it. What lands here is the residue - constraints that need to
 * compare one part of the document against another.
 *
 * <p>The reason uniqueness is checked here rather than with the schema's
 * {@code uniqueItems} is precision. {@code uniqueItems} compares whole objects,
 * so two environments both named {@code production} with different URLs would
 * pass, and even when it did fire it could only point at the array. This points
 * at {@code /spec/environments/1/name} - the actual duplicate - and names where
 * the first one was.
 *
 * <p>{@code examples/services/invalid/expected.json} records what these rules
 * produce, and both the Node checker and {@code ManifestValidationTest} assert
 * against that same file.
 */
@Component
public class ManifestSemantics {

    public void check(ManifestV1 manifest) {
        List<Violation> violations = new ArrayList<>();
        duplicateEnvironmentNames(manifest, violations);
        duplicateDependencyNames(manifest, violations);
        duplicateJourneys(manifest, violations);

        if (!violations.isEmpty()) {
            throw new ValidationFailedException(
                    violations.size() == 1
                            ? "The document is well formed but inconsistent: 1 problem"
                            : "The document is well formed but inconsistent: " + violations.size() + " problems",
                    violations);
        }
    }

    private static void duplicateEnvironmentNames(ManifestV1 manifest, List<Violation> violations) {
        Map<String, Integer> firstSeenAt = new HashMap<>();
        List<ManifestV1.Environment> environments = manifest.spec().environments();

        for (int i = 0; i < environments.size(); i++) {
            String name = environments.get(i).name();
            Integer first = firstSeenAt.putIfAbsent(name, i);
            if (first != null) {
                violations.add(new Violation(
                        "/spec/environments/" + i + "/name",
                        "duplicate",
                        "an environment name not already used in this document",
                        name,
                        "The environment '" + name + "' is declared twice; the first is at /spec/environments/"
                                + first + "/name. Each environment is a distinct place the service runs, so two"
                                + " entries with the same name cannot both be stored."));
            }
        }
    }

    private static void duplicateDependencyNames(ManifestV1 manifest, List<Violation> violations) {
        manifest.spec().dependencies().ifPresent(dependencies -> {
            Map<String, Integer> firstSeenAt = new HashMap<>();
            for (int i = 0; i < dependencies.size(); i++) {
                String name = dependencies.get(i).name();
                Integer first = firstSeenAt.putIfAbsent(name, i);
                if (first != null) {
                    violations.add(new Violation(
                            "/spec/dependencies/" + i,
                            "duplicate",
                            "a dependency not already listed in this document",
                            name,
                            "The dependency '" + name + "' is listed twice; the first is at /spec/dependencies/"
                                    + first + ". Listing it twice does not make it two dependencies."));
                }
            }
        });
    }

    private static void duplicateJourneys(ManifestV1 manifest, List<Violation> violations) {
        manifest.spec().journeys().ifPresent(journeys -> {
            Map<String, Integer> firstSeenAt = new HashMap<>();
            for (int i = 0; i < journeys.size(); i++) {
                String journey = journeys.get(i);
                Integer first = firstSeenAt.putIfAbsent(journey, i);
                if (first != null) {
                    violations.add(new Violation(
                            "/spec/journeys/" + i,
                            "duplicate",
                            "a journey not already listed in this document",
                            journey,
                            "The journey '" + journey + "' is listed twice; the first is at /spec/journeys/" + first
                                    + ". A duplicated journey would be double-counted in any blast-radius answer."));
                }
            }
        });
    }
}
