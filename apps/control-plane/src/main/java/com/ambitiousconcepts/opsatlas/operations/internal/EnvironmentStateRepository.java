package com.ambitiousconcepts.opsatlas.operations.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Current state per environment. Every method takes orgId first; see ADR 0003.
 *
 * <p>A top-level interface rather than one nested in a holder class: Spring Data
 * does not scan nested interfaces, and the failure is a missing bean at startup
 * rather than anything that points at the cause.
 */
interface EnvironmentStateRepository extends JpaRepository<EnvironmentStateEntity, UUID> {

    List<EnvironmentStateEntity> findByOrgIdAndServiceId(UUID orgId, UUID serviceId);

    List<EnvironmentStateEntity> findByOrgIdAndServiceIdIn(UUID orgId, List<UUID> serviceIds);

    Optional<EnvironmentStateEntity> findByOrgIdAndEnvironmentId(UUID orgId, UUID environmentId);
}
