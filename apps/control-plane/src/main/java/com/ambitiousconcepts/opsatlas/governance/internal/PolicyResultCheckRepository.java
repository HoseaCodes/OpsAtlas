package com.ambitiousconcepts.opsatlas.governance.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PolicyResultCheckRepository
        extends JpaRepository<PolicyResultCheckEntity, PolicyResultCheckEntity.Key> {

    List<PolicyResultCheckEntity> findByPolicyResultIdOrderByCheckIdAsc(UUID policyResultId);
}
