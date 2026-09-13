package com.ambitiousconcepts.opsatlas.operations.internal;

import com.ambitiousconcepts.opsatlas.operations.api.EnvironmentHealth;
import com.ambitiousconcepts.opsatlas.operations.api.HealthReadModel;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultHealthReadModel implements HealthReadModel {

    /**
     * Worst-first, so a rollup across environments is a minimum.
     *
     * <p>A service whose production is down and whose staging is fine is down.
     * Averaging, or taking the most common status, would report it as mostly
     * healthy - which is true of the environments and false of the service.
     */
    private static final List<String> BY_SEVERITY = List.of("DOWN", "DEGRADED", "HEALTHY");

    private final EnvironmentStateRepository states;
    private final EnvironmentDayRepository days;
    private final Clock clock;

    DefaultHealthReadModel(
            EnvironmentStateRepository states,
            EnvironmentDayRepository days,
            Clock clock) {
        this.states = states;
        this.days = days;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, EnvironmentHealth> forService(UUID orgId, UUID serviceId, int window) {
        LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(window - 1L);

        Map<UUID, EnvironmentHealth> health = new LinkedHashMap<>();
        for (EnvironmentStateEntity state : states.findByOrgIdAndServiceId(orgId, serviceId)) {
            List<EnvironmentDayEntity> rows = days.findSince(orgId, state.getEnvironmentId(), from);
            health.put(state.getEnvironmentId(), toHealth(state, rows));
        }
        return health;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> rollupStatus(UUID orgId, UUID serviceId) {
        return worst(states.findByOrgIdAndServiceId(orgId, serviceId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> rollupStatuses(UUID orgId, List<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }

        // One query for the whole page. The catalog list would otherwise make a
        // round trip per service, which is the classic N+1 that only hurts once
        // the fleet is big enough for it to matter.
        Map<UUID, List<EnvironmentStateEntity>> byService = new HashMap<>();
        for (EnvironmentStateEntity state : states.findByOrgIdAndServiceIdIn(orgId, serviceIds)) {
            byService.computeIfAbsent(state.getServiceId(), id -> new ArrayList<>()).add(state);
        }

        Map<UUID, String> rollups = new HashMap<>();
        byService.forEach((serviceId, environments) -> worst(environments).ifPresent(status ->
                rollups.put(serviceId, status)));
        return rollups;
    }

    private static Optional<String> worst(List<EnvironmentStateEntity> environments) {
        // Empty rather than a default: a service nobody has observed has no
        // health, which is a different fact from being unhealthy, and the
        // console renders it differently.
        return environments.stream()
                .map(EnvironmentStateEntity::getStatus)
                .min(Comparator.comparingInt(BY_SEVERITY::indexOf));
    }

    private static EnvironmentHealth toHealth(EnvironmentStateEntity state, List<EnvironmentDayEntity> rows) {
        List<EnvironmentHealth.DailyAvailability> daily = rows.stream()
                .map(row -> new EnvironmentHealth.DailyAvailability(
                        row.getDay().toString(),
                        row.getProbes(),
                        row.getSuccesses(),
                        row.getProbes() == 0 ? 0d : (double) row.getSuccesses() / row.getProbes(),
                        row.getMeanResponseMs(),
                        row.getMaxResponseMs()))
                .toList();

        long probes = rows.stream().mapToLong(EnvironmentDayEntity::getProbes).sum();
        long successes = rows.stream().mapToLong(EnvironmentDayEntity::getSuccesses).sum();

        return new EnvironmentHealth(
                state.getEnvironmentId(),
                state.getStatus(),
                state.getDetail(),
                state.getLastProbeAt(),
                state.getLastHealthyAt(),
                state.getConsecutiveFailures(),
                state.getResponseMs(),
                // Null rather than 1.0 when nothing has been probed in the
                // window: no probes is not perfect availability.
                probes == 0 ? null : (double) successes / probes,
                daily);
    }
}
