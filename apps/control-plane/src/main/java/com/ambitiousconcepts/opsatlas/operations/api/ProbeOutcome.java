package com.ambitiousconcepts.opsatlas.operations.api;

/**
 * What a single probe found.
 *
 * <p>Four outcomes rather than a boolean, because they call for different
 * responses and the console shows them differently. A service that answers 503
 * is up and saying it is not ready; a service that times out may be wedged, and
 * a connection refused means nothing is listening at all. Collapsing them into
 * "failed" would throw away the first thing an on-call engineer asks.
 */
public enum ProbeOutcome {
    /** The endpoint answered with a success status. */
    HEALTHY,

    /** It answered, but not with success - a 503 from a readiness endpoint is the common case. */
    UNHEALTHY,

    /** It did not answer within the probe's timeout. */
    TIMEOUT,

    /** Nothing was listening, DNS failed, or the connection was refused or reset. */
    UNREACHABLE;

    public boolean succeeded() {
        return this == HEALTHY;
    }
}
