package com.ambitiousconcepts.opsatlas.integrations.api;

import java.util.Optional;

/**
 * What happened when a manifest was fetched.
 *
 * <p>A sealed result rather than an exception, because none of these outcomes is
 * exceptional. A repository being unreachable, rate-limited or simply not having
 * a manifest yet are all ordinary states a watched source passes through, and
 * the caller has to record each of them differently. Throwing would make the
 * caller reconstruct the distinction from exception types.
 */
public sealed interface FetchResult {

    /** The manifest was read. */
    record Content(String document, Optional<String> etag) implements FetchResult {}

    /** The provider confirmed nothing changed since the stored ETag. No bytes transferred. */
    record NotModified() implements FetchResult {}

    /** The repository, ref or path does not exist. Distinct from a failure: nothing is wrong with us. */
    record NotFound(String detail) implements FetchResult {}

    /** Credentials are missing, rejected, or lack the scope to read this repository. */
    record Unauthorized(String detail) implements FetchResult {}

    /** The provider refused for quota reasons. Carries when it is expected to relent, where it said. */
    record RateLimited(String detail) implements FetchResult {}

    /** The provider could not be contacted, or answered in a way that was not usable. */
    record Unreachable(String detail) implements FetchResult {}
}
