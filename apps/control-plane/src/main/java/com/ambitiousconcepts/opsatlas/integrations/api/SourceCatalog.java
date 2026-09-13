package com.ambitiousconcepts.opsatlas.integrations.api;

import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import java.util.UUID;

/** Managing the repositories OpsAtlas watches. */
public interface SourceCatalog {

    /**
     * Start watching a repository. Idempotent: watching an already-watched
     * location returns the existing source rather than creating a second one,
     * because two sources polling the same file would fight over one service row.
     */
    SourceView watch(UUID orgId, String provider, SourceRef ref);

    PageResponse<SourceView> list(UUID orgId, String cursor, int limit);

    SourceView get(UUID orgId, UUID sourceId);

    /** Sync one source now, rather than waiting for the next scheduled pass. */
    SourceView syncNow(UUID orgId, UUID sourceId);

    /** Stop or resume polling without losing the record of what it was pointed at. */
    SourceView setEnabled(UUID orgId, UUID sourceId, boolean enabled);

    /**
     * Stop watching. The service the source produced is left registered: a
     * repository no longer being watched is not evidence that its service
     * stopped existing, and deciding that is not this call's business.
     */
    void unwatch(UUID orgId, UUID sourceId);
}
