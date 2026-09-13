package com.ambitiousconcepts.opsatlas.integrations.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Source persistence. Every org-scoped method takes orgId first; see ADR 0003. */
interface SourceRepository extends JpaRepository<SourceEntity, UUID> {

    List<SourceEntity> findByOrgIdOrderByIdAsc(UUID orgId, Limit limit);

    List<SourceEntity> findByOrgIdAndIdGreaterThanOrderByIdAsc(UUID orgId, UUID afterId, Limit limit);

    Optional<SourceEntity> findByOrgIdAndId(UUID orgId, UUID id);

    Optional<SourceEntity> findByOrgIdAndProviderAndRepositoryAndPath(
            UUID orgId, String provider, String repository, String path);

    /**
     * The scheduler's query, and the one place a query is deliberately not
     * org-scoped: the poller runs on a timer rather than inside a request, so
     * there is no principal to scope it to. Each source carries its own
     * {@code org_id}, which is what every write the sync performs is scoped by.
     */
    @Query("""
            select s from SourceEntity s
            where s.enabled = true
            order by s.lastAttemptAt asc nulls first, s.id asc
            """)
    List<SourceEntity> findDueForSync(Limit limit);
}
