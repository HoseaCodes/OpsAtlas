package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.internal.domain.EnvironmentEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Environment persistence. Every method takes orgId first; see ADR 0003. */
public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, UUID> {

    List<EnvironmentEntity> findByOrgIdAndServiceIdOrderByNameAsc(UUID orgId, UUID serviceId);

    List<EnvironmentEntity> findByOrgIdAndServiceIdIn(UUID orgId, List<UUID> serviceIds);

    List<EnvironmentEntity> findByOrgIdAndIdIn(UUID orgId, List<UUID> ids);

    java.util.Optional<EnvironmentEntity> findByOrgIdAndServiceIdAndName(UUID orgId, UUID serviceId, String name);
}
