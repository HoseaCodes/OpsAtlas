package com.ambitiousconcepts.opsatlas.catalog.internal.ingest;

import java.util.List;
import java.util.Optional;

/**
 * A validated service.yaml, version 1.
 *
 * <p>Stage 4 of ADR 0002. These records are only ever built from a document that
 * has already passed schema validation, which is why they can afford to be plain
 * data with no validation of their own.
 *
 * <p>They are built by {@link ManifestBinder} reading an explicit field at a
 * time, not by Jackson data binding. That is deliberate: ADR 0002's whole point
 * is that repository content is never deserialized into types it can influence,
 * and a hand-written binder keeps that property obvious rather than dependent on
 * how an {@code ObjectMapper} happens to be configured.
 *
 * <p>Optional is used for fields a manifest may legitimately omit, so that
 * "absent" is visible in the type rather than encoded as a null a caller may
 * forget about. The scorecard's job is to judge the absences; this layer only
 * has to represent them faithfully.
 */
public record ManifestV1(String apiVersion, Metadata metadata, Spec spec) {

    public record Metadata(
            String name,
            Optional<String> displayName,
            Optional<String> owner,
            String repository,
            Optional<String> description) {}

    public record Spec(
            int tier,
            String lifecycle,
            Optional<String> runtime,
            List<Environment> environments,
            Optional<Health> health,
            Optional<Observability> observability,
            Optional<Operations> operations,
            /**
             * Absent and empty are different answers. "I have not said" is a gap;
             * "I have none" is a statement. The dependencies-declared and
             * journeys-declared checks rely on being able to tell them apart.
             */
            Optional<List<String>> journeys,
            Optional<List<Dependency>> dependencies) {}

    public record Environment(String name, Optional<String> url) {}

    public record Health(Optional<String> readiness, Optional<String> liveness) {}

    public record Observability(Optional<String> serviceName, Optional<String> dashboard) {}

    public record Operations(Optional<Slo> slo, Optional<String> runbook, Optional<String> contact) {}

    public record Slo(double availability, String window) {}

    public record Dependency(String name, DependencyKind kind) {}

    /**
     * What a dependency is, so that nothing downstream has to guess from the
     * name. A flat list of strings forces exactly that guess, and a guess
     * becomes wrong data on a page someone reads during an incident.
     */
    public enum DependencyKind {
        /** Another service that could itself be registered in this catalog. */
        SERVICE,
        /** A database, cache, queue or bucket. */
        DATASTORE,
        /** A third party outside the organisation. */
        EXTERNAL;

        static DependencyKind parse(String value) {
            // The schema constrains this to the three values below, so an
            // unrecognised one cannot reach here from a validated document.
            return switch (value) {
                case "datastore" -> DATASTORE;
                case "external" -> EXTERNAL;
                default -> SERVICE;
            };
        }

        public String wireValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
