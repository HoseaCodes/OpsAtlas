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

    /**
     * One environment of one service, by the name the manifest gave it.
     *
     * <p>Added for deployment reporting: a pipeline knows it deployed
     * "production", not the UUID this catalog assigned. Scoped by organization
     * and by service, so a name that exists elsewhere resolves to nothing here.
     */
    Optional<UUID> environmentIdByName(UUID orgId, UUID serviceId, String name);

    /**
     * Environment id to name for one service.
     *
     * <p>So a module that stores rows keyed by environment id can label them
     * without a second round trip and without reading catalog's tables. Empty
     * when the service does not exist here, which is also what makes it safe to
     * build an {@code IN} clause from: a caller can only ever ask about
     * environments this organization owns.
     */
    Map<UUID, String> namesOf(UUID orgId, UUID serviceId);
}
