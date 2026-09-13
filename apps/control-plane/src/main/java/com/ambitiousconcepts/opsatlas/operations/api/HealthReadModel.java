package com.ambitiousconcepts.opsatlas.operations.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading health, for the catalog.
 *
 * <p>{@code catalog} depends on this rather than the reverse: a service's health
 * is something {@code operations} knows, and the catalog asks. An environment
 * with no entry has never been observed.
 */
public interface HealthReadModel {

    /** Health for every environment of one service, keyed by environment id. */
    Map<UUID, EnvironmentHealth> forService(UUID orgId, UUID serviceId, int days);

    /**
     * The worst status across a service's environments, for the catalog list.
     *
     * <p>Empty when no environment of the service has ever been observed -
     * which is every service until an observer runs.
     */
    Optional<String> rollupStatus(UUID orgId, UUID serviceId);

    /** Rollup statuses for many services at once, so the list view is one query rather than N. */
    Map<UUID, String> rollupStatuses(UUID orgId, List<UUID> serviceIds);
}
