package com.ambitiousconcepts.opsatlas.identity.api;

import java.util.function.Supplier;

/**
 * Runs work as a named principal, outside a request.
 *
 * <p>Most of this system runs inside an HTTP request, where a filter binds the
 * caller and {@link CurrentPrincipal} can simply read it. Scheduled work has no
 * request and therefore no caller - but it still acts, and what it does still
 * has to be attributable.
 *
 * <p>The alternative was to let {@code CurrentPrincipal} fall back to some
 * "system" default when nothing is bound. That was rejected: the fallback would
 * fire just as readily when a <em>request</em> lost its principal through a bug,
 * silently attributing a user's action to the system and removing the loud
 * failure that would otherwise expose the bug. Scheduled work says who it is
 * instead.
 *
 * <p>Bindings nest. A manual sync triggered from a request runs inside that
 * request's principal, and this restores it afterwards rather than clearing it.
 */
public interface PrincipalScope {

    /**
     * @param principal who the work runs as. Use a name that identifies the
     *     mechanism - an audit reader seeing it should learn that a poller did
     *     this, not a person.
     */
    <T> T runAs(Principal principal, Supplier<T> work);
}
