package com.ambitiousconcepts.opsatlas.governance.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Audit persistence. Insert and read only - there is no update or delete path. */
interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    List<AuditEventEntity> findByOrgIdOrderByOccurredAtDescIdDesc(UUID orgId, Limit limit);

    List<AuditEventEntity> findByOrgIdAndIdLessThanOrderByOccurredAtDescIdDesc(
            UUID orgId, UUID beforeId, Limit limit);

    List<AuditEventEntity> findByOrgIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(
            UUID orgId, String subjectType, UUID subjectId, Limit limit);
}
