package com.ambitiousconcepts.opsatlas.governance.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Scorecard persistence. Every method takes orgId first; see ADR 0003. */
interface PolicyResultRepository extends JpaRepository<PolicyResultEntity, UUID> {

    Optional<PolicyResultEntity> findFirstByOrgIdAndServiceIdOrderByEvaluatedAtDescIdDesc(
            UUID orgId, UUID serviceId);

    List<PolicyResultEntity> findByOrgIdAndServiceIdOrderByEvaluatedAtDescIdDesc(
            UUID orgId, UUID serviceId, Limit limit);
}
