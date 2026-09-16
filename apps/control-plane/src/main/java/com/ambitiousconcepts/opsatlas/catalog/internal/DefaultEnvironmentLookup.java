package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.api.EnvironmentLookup;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.EnvironmentEntity;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.ServiceEntity;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultEnvironmentLookup implements EnvironmentLookup {

    private final EnvironmentRepository environments;
    private final ServiceRepository services;

    DefaultEnvironmentLookup(EnvironmentRepository environments, ServiceRepository services) {
        this.environments = environments;
        this.services = services;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UUID> servicesOwning(UUID orgId, Collection<UUID> environmentIds) {
        if (environmentIds.isEmpty()) {
            return Map.of();
        }
        return environments.findByOrgIdAndIdIn(orgId, List.copyOf(environmentIds)).stream()
                .collect(Collectors.toMap(EnvironmentEntity::getId, EnvironmentEntity::getServiceId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> serviceIdBySlug(UUID orgId, String slug) {
        return services.findByOrgIdAndSlug(orgId, slug).map(ServiceEntity::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> environmentIdByName(UUID orgId, UUID serviceId, String name) {
        return environments.findByOrgIdAndServiceIdAndName(orgId, serviceId, name).map(EnvironmentEntity::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> namesOf(UUID orgId, UUID serviceId) {
        return environments.findByOrgIdAndServiceIdOrderByNameAsc(orgId, serviceId).stream()
                .collect(Collectors.toMap(EnvironmentEntity::getId, EnvironmentEntity::getName));
    }
}
