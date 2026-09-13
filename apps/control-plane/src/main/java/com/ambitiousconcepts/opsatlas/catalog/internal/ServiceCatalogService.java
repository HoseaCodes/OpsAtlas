package com.ambitiousconcepts.opsatlas.catalog.internal;

import com.ambitiousconcepts.opsatlas.catalog.api.ServiceCatalog;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceDetail;
import com.ambitiousconcepts.opsatlas.catalog.api.ServiceSummary;
import com.ambitiousconcepts.opsatlas.catalog.internal.domain.ServiceEntity;
import com.ambitiousconcepts.opsatlas.shared.Cursor;
import com.ambitiousconcepts.opsatlas.shared.NotFoundException;
import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ServiceCatalogService implements ServiceCatalog {

    private final ServiceRepository services;
    private final ServiceDetailAssembler assembler;

    ServiceCatalogService(ServiceRepository services, ServiceDetailAssembler assembler) {
        this.services = services;
        this.assembler = assembler;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ServiceSummary> list(UUID orgId, String cursor, int limit) {
        // One row beyond the page is fetched to determine whether another page
        // exists. The alternative - a COUNT on every request - is a second scan
        // to answer a question the caller did not ask.
        Limit fetch = Limit.of(limit + 1);

        List<ServiceEntity> rows = cursor == null
                ? services.findByOrgIdOrderByIdAsc(orgId, fetch)
                : services.findByOrgIdAndIdGreaterThanOrderByIdAsc(orgId, Cursor.decode(cursor), fetch);

        boolean hasMore = rows.size() > limit;
        List<ServiceEntity> page = hasMore ? rows.subList(0, limit) : rows;

        List<ServiceSummary> items = page.stream().map(ServiceCatalogService::toSummary).toList();
        String nextCursor = hasMore ? Cursor.encode(page.get(page.size() - 1).getId()) : null;

        return PageResponse.of(items, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceDetail get(UUID orgId, String slug) {
        return services.findByOrgIdAndSlug(orgId, slug)
                .map(service -> assembler.assemble(orgId, service))
                .orElseThrow(() -> new NotFoundException("Service", slug));
    }

    private static ServiceSummary toSummary(ServiceEntity entity) {
        return new ServiceSummary(
                entity.getId(),
                entity.getSlug(),
                entity.getDisplayName(),
                entity.getRepository(),
                entity.getTier(),
                entity.getRuntime(),
                entity.getLifecycle(),
                entity.getTeamId() != null,
                entity.getCreatedAt(),
                entity.getVersion());
    }
}
