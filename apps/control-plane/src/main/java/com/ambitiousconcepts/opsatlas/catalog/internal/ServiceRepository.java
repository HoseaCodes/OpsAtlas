package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.internal.domain.ServiceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Catalog persistence.
 *
 * <p><strong>Every method takes {@code orgId} as its first parameter.</strong>
 * There is no ambient tenant filter and no Hibernate {@code @Filter}: scoping is
 * visible in the signature so that reviewing whether a query is scoped requires
 * reading only that query. See {@code docs/adr/0003-org-scoping-stub.md}, which
 * also states the cost - nothing in the type system enforces this, and
 * {@code OrgIsolationIT} is what actually catches a mistake.
 *
 * <p>Pagination is keyset, never offset (CLAUDE.md section 9). Row ids are
 * UUIDv7, so ordering by id is ordering by registration time and the
 * {@code (org_id, id)} index serves the scan directly.
 */
public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

    /** First page. */
    List<ServiceEntity> findByOrgIdOrderByIdAsc(UUID orgId, Limit limit);

    /** Subsequent pages, starting strictly after the last id of the previous one. */
    List<ServiceEntity> findByOrgIdAndIdGreaterThanOrderByIdAsc(UUID orgId, UUID afterId, Limit limit);

    Optional<ServiceEntity> findByOrgIdAndSlug(UUID orgId, String slug);

    Optional<ServiceEntity> findByOrgIdAndId(UUID orgId, UUID id);
}
