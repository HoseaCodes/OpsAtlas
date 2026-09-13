package com.ambitiousconcepts.opsatlas.integrations.internal;

import com.ambitiousconcepts.opsatlas.shared.NotFoundException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional edges of a sync.
 *
 * <p>A separate bean rather than methods on {@link SourceSyncService}, because
 * Spring's transaction proxy is bypassed by self-invocation: calling a
 * {@code @Transactional} method on {@code this} silently runs without a
 * transaction, which is exactly the kind of bug that only shows up under
 * failure.
 *
 * <p>Each method is {@code REQUIRES_NEW}, and that is the point. Registration is
 * itself transactional (a service row, its scorecard and its audit event commit
 * together - CLAUDE.md §8). If the source's bookkeeping shared that transaction,
 * a rejected manifest would mark it rollback-only, and the sync would then be
 * unable to record <em>why</em> it failed: the outcome write would fail too, and
 * the source would look as though it had never been attempted. Separating them
 * is what lets a failure be both rolled back and reported.
 */
@Component
class SourceStore {

    private final SourceRepository sources;
    private final Clock clock;

    SourceStore(SourceRepository sources, Clock clock) {
        this.sources = sources;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    SourceEntity load(UUID orgId, UUID sourceId) {
        return sources.findByOrgIdAndId(orgId, sourceId)
                .orElseThrow(() -> new NotFoundException("Source", sourceId.toString()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SourceEntity recordSuccess(UUID orgId, UUID sourceId, String outcome, UUID serviceId, String etag) {
        SourceEntity source = load(orgId, sourceId);
        source.succeeded(outcome, serviceId, etag, clock.instant());
        return sources.save(source);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SourceEntity recordFailure(UUID orgId, UUID sourceId, String outcome, String detail) {
        SourceEntity source = load(orgId, sourceId);
        source.failed(outcome, truncate(detail), clock.instant());
        return sources.save(source);
    }

    /** Sync state is shown in a table cell, not a log viewer. */
    private static String truncate(String detail) {
        if (detail == null || detail.isBlank()) {
            return "The sync failed without a reported reason, which is a defect in the control plane.";
        }
        return detail.length() <= 1000 ? detail : detail.substring(0, 997) + "…";
    }
}
