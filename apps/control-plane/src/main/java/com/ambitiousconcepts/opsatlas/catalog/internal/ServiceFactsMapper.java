package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.internal.ingest.ManifestV1;
import com.ambitiousconcepts.opsatlas.governance.api.ServiceFacts;
import java.util.List;
import java.util.UUID;

/**
 * Translates a validated manifest into the facts governance scores.
 *
 * <p>This mapping exists so the two modules do not share a type. Governance owns
 * {@link ServiceFacts} and states its inputs in its own terms; catalog owns the
 * manifest and knows how to answer them. The dependency runs one way - catalog
 * calls governance when it registers something - and this class is the only
 * place that knows both shapes.
 *
 * <p>When the observer lands, {@code ServiceFacts} grows fields that no manifest
 * can supply, and they will be filled from somewhere other than here. No policy
 * check changes.
 */
final class ServiceFactsMapper {

    private ServiceFactsMapper() {}

    static ServiceFacts from(UUID serviceId, ManifestV1 manifest) {
        return new ServiceFacts(
                serviceId,
                manifest.metadata().name(),
                manifest.spec().tier(),
                manifest.metadata().owner(),
                manifest.spec().environments().stream()
                        .map(environment -> new ServiceFacts.EnvironmentFacts(environment.name(), environment.url()))
                        .toList(),
                manifest.spec().health().flatMap(ManifestV1.Health::readiness),
                manifest.spec().health().flatMap(ManifestV1.Health::liveness),
                manifest.spec().observability().flatMap(ManifestV1.Observability::serviceName),
                manifest.spec().operations().flatMap(ManifestV1.Operations::runbook),
                manifest.spec()
                        .operations()
                        .flatMap(ManifestV1.Operations::slo)
                        .map(slo -> new ServiceFacts.Slo(slo.availability(), slo.window())),
                manifest.spec().journeys(),
                // Absence is preserved deliberately: "I have not said" and "I have
                // none" are different answers, and dependencies-declared is the
                // rule that distinguishes them.
                manifest.spec().dependencies().map(ServiceFactsMapper::names));
    }

    private static List<String> names(List<ManifestV1.Dependency> dependencies) {
        return dependencies.stream().map(ManifestV1.Dependency::name).toList();
    }
}
