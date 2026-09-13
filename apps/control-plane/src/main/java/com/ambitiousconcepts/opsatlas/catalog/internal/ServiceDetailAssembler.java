package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.api.ServiceDetail;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.EnvironmentEntity;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.ServiceEntity;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.TeamEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns stored rows into the {@link ServiceDetail} the API returns. */
@Component
class ServiceDetailAssembler {

    private final EnvironmentRepository environments;
    private final TeamRepository teams;
    private final ObjectMapper objectMapper;

    ServiceDetailAssembler(EnvironmentRepository environments, TeamRepository teams, ObjectMapper objectMapper) {
        this.environments = environments;
        this.teams = teams;
        this.objectMapper = objectMapper;
    }

    ServiceDetail assemble(UUID orgId, ServiceEntity service) {
        List<ServiceDetail.EnvironmentView> views =
                environments.findByOrgIdAndServiceIdOrderByNameAsc(orgId, service.getId()).stream()
                        .map(ServiceDetailAssembler::toView)
                        .toList();

        String owner = service.getTeamId() == null
                ? null
                : teams.findById(service.getTeamId())
                        .map(TeamEntity::getSlug)
                        .orElse(null);

        return new ServiceDetail(
                service.getId(),
                service.getSlug(),
                service.getDisplayName(),
                service.getRepository(),
                service.getTier(),
                service.getRuntime(),
                service.getLifecycle(),
                owner,
                service.getSchemaVersion(),
                service.getManifestDigest(),
                service.getSourcePath(),
                service.getSourceRef(),
                views,
                readManifest(service),
                service.getCreatedAt(),
                service.getUpdatedAt(),
                service.getVersion());
    }

    private static ServiceDetail.EnvironmentView toView(EnvironmentEntity environment) {
        return new ServiceDetail.EnvironmentView(
                environment.getId(),
                environment.getName(),
                environment.getUrl(),
                environment.getReadinessPath(),
                environment.getLivenessPath(),
                // Always null in slice one: nothing observes anything yet. The
                // console renders "never observed" from this rather than
                // inferring it from a missing field.
                null);
    }

    private com.fasterxml.jackson.databind.JsonNode readManifest(ServiceEntity service) {
        try {
            return objectMapper.readTree(service.getManifest());
        } catch (JsonProcessingException e) {
            // The column only ever receives output from ManifestIngestor, so
            // this means the stored document was corrupted after the fact.
            throw new IllegalStateException(
                    "The stored manifest for service " + service.getSlug() + " is not valid JSON", e);
        }
    }
}
