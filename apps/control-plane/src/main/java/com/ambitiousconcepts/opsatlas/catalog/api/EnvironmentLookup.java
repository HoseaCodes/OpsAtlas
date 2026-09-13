package com.ambitiousconcepts.opsatlas.catalog.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolving environments, for modules that are handed environment ids.
 *
 * <p>This exists for {@code operations}: an observer reports environment ids,
 * and the rollups are keyed by service, so something has to join the two. Before
 * this, {@code operations} did it with a {@code SELECT} against catalog's
 * {@code environment} table - a boundary violation no test could catch, because
 * a SQL string is not an import. See
 * {@code docs/adr/0010-health-is-served-separately-from-the-catalog.md}.
 */
public interface EnvironmentLookup {

    /**
     * Which service owns each of these environments.
     *
     * <p>Scoped by organization, so an id belonging to another organization is
     * simply absent from the result - never resolved, never reported as
     * belonging to something.
     *
     * @return environment id to service id, omitting ids that do not exist here
     */
    Map<UUID, UUID> servicesOwning(UUID orgId, Collection<UUID> environmentIds);

    /** The service registered under this slug, if there is one. */
    Optional<UUID> serviceIdBySlug(UUID orgId, String slug);
}
