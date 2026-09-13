package com.ambitiousconcepts.opsatlas.identity.internal;

import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.identity.api.Principal;
import org.springframework.stereotype.Component;

/**
 * Holds the resolved principal for the duration of one request.
 *
 * <p>Backed by a thread local that {@link OrgContextFilter} sets on the way in
 * and clears in a {@code finally} on the way out. Leaving a principal behind on
 * a pooled request thread would let the next request inherit another caller's
 * organization, so the clear is not optional and is tested.
 */
@Component
class RequestScopedPrincipal implements CurrentPrincipal {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    @Override
    public Principal get() {
        Principal principal = CURRENT.get();
        if (principal == null) {
            throw new IllegalStateException(
                    "No principal bound to this thread. Something is reading the current organization "
                            + "outside a request, which means it is not scoped to one.");
        }
        return principal;
    }

    static void bind(Principal principal) {
        CURRENT.set(principal);
    }

    static void clear() {
        CURRENT.remove();
    }
}
