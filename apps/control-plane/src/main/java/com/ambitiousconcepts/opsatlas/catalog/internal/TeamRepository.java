package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.internal.domain.TeamEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Team persistence. Every method takes orgId first; see ADR 0003. */
public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    Optional<TeamEntity> findByOrgIdAndSlug(UUID orgId, String slug);
}
