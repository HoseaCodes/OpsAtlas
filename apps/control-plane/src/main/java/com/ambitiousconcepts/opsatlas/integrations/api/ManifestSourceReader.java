package com.ambitiousconcepts.opsatlas.integrations.api;

import java.util.Optional;

/**
 * Reads a manifest from wherever it lives.
 *
 * <p><strong>Read-only, structurally.</strong> There is no write method, so
 * there is no code path that could modify a monitored repository. ADR 0008: the
 * whole reason OpsAtlas polls rather than registering webhooks is to avoid ever
 * holding write access, and an interface with one read method is what makes that
 * claim checkable rather than aspirational.
 *
 * <p>This is also the seam a GitHub App swaps in at. Replacing the implementation
 * changes authentication and rate limits and nothing else.
 */
public interface ManifestSourceReader {

    /** Which provider this reader handles, matching {@code source.provider}. */
    String provider();

    /**
     * @param etag the ETag from the previous successful fetch, replayed as
     *     {@code If-None-Match}. Empty on a first fetch. A provider that honours
     *     it answers {@link FetchResult.NotModified} and transfers nothing.
     */
    FetchResult fetch(SourceRef source, Optional<String> etag);
}
