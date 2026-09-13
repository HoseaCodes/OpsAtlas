package com.ambitiousconcepts.opsatlas.integrations.internal;

import com.ambitiousconcepts.opsatlas.integrations.api.ManifestSourceReader;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceCatalog;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceRef;
import com.ambitiousconcepts.opsatlas.integrations.api.SourceView;
import com.ambitiousconcepts.opsatlas.shared.ConflictException;
import com.ambitiousconcepts.opsatlas.shared.Cursor;
import com.ambitiousconcepts.opsatlas.shared.NotFoundException;
import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultSourceCatalog implements SourceCatalog {

    private final SourceRepository sources;
    private final SourceSyncService sync;
    private final Set<String> knownProviders;
    private final Clock clock;

    DefaultSourceCatalog(
            SourceRepository sources, SourceSyncService sync, List<ManifestSourceReader> readers, Clock clock) {
        this.sources = sources;
        this.sync = sync;
        this.knownProviders = readers.stream().map(ManifestSourceReader::provider).collect(Collectors.toUnmodifiableSet());
        this.clock = clock;
    }

    @Override
    @Transactional
    public SourceView watch(UUID orgId, String provider, SourceRef ref) {
        if (!knownProviders.contains(provider)) {
            throw new ConflictException(
                    "No reader is configured for provider '" + provider + "'.",
                    new Violation(
                            "/provider",
                            "unsupported",
                            "one of: " + String.join(", ", knownProviders),
                            provider,
                            "OpsAtlas can only read manifests from: " + String.join(", ", knownProviders) + "."));
        }

        // Idempotent by location, matching how a service is identified for
        // re-registration (ADR 0007).
        return sources.findByOrgIdAndProviderAndRepositoryAndPath(orgId, provider, ref.repository(), ref.path())
                .map(SourceMapper::toView)
                .orElseGet(() -> SourceMapper.toView(
                        sources.save(SourceEntity.watch(orgId, provider, ref, clock.instant()))));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SourceView> list(UUID orgId, String cursor, int limit) {
        Limit fetch = Limit.of(limit + 1);
        List<SourceEntity> rows = cursor == null
                ? sources.findByOrgIdOrderByIdAsc(orgId, fetch)
                : sources.findByOrgIdAndIdGreaterThanOrderByIdAsc(orgId, Cursor.decode(cursor), fetch);

        boolean hasMore = rows.size() > limit;
        List<SourceEntity> page = hasMore ? rows.subList(0, limit) : rows;

        return PageResponse.of(
                page.stream().map(SourceMapper::toView).toList(),
                hasMore ? Cursor.encode(page.get(page.size() - 1).getId()) : null);
    }

    @Override
    @Transactional(readOnly = true)
    public SourceView get(UUID orgId, UUID sourceId) {
        return SourceMapper.toView(require(orgId, sourceId));
    }

    @Override
    public SourceView syncNow(UUID orgId, UUID sourceId) {
        // Deliberately not @Transactional: a manual sync must behave exactly
        // like a scheduled one, and a scheduled one runs with no ambient
        // transaction. Enrolling it in one here would reintroduce the
        // rollback-only trap SourceStore exists to avoid.
        return sync.sync(orgId, sourceId);
    }

    @Override
    @Transactional
    public SourceView setEnabled(UUID orgId, UUID sourceId, boolean enabled) {
        SourceEntity source = require(orgId, sourceId);
        source.setEnabled(enabled, clock.instant());
        return SourceMapper.toView(sources.save(source));
    }

    @Override
    @Transactional
    public void unwatch(UUID orgId, UUID sourceId) {
        sources.delete(require(orgId, sourceId));
    }

    private SourceEntity require(UUID orgId, UUID sourceId) {
        // Scoped by organization, so a source belonging to another organization
        // reports as absent rather than forbidden (ADR 0003).
        return sources.findByOrgIdAndId(orgId, sourceId)
                .orElseThrow(() -> new NotFoundException("Source", sourceId.toString()));
    }
}
