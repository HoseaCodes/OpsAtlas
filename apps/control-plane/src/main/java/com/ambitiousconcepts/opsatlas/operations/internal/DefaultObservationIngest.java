package com.ambitiousconcepts.opsatlas.operations.internal;

import com.ambitiousconcepts.opsatlas.catalog.api.EnvironmentLookup;
import com.ambitiousconcepts.opsatlas.operations.api.Observation;
import com.ambitiousconcepts.opsatlas.operations.api.ObservationBatch;
import com.ambitiousconcepts.opsatlas.operations.api.ObservationIngest;
import com.ambitiousconcepts.opsatlas.shared.ValidationFailedException;
import com.ambitiousconcepts.opsatlas.shared.Violation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds reported probe results into the rolled-up state.
 *
 * <p>The whole batch is one transaction. Counters for one environment are
 * touched once rather than once per observation, and a batch either lands
 * completely or not at all - a half-applied batch would leave probe counts and
 * success counts disagreeing with each other, which is unrecoverable because the
 * individual results were never stored.
 */
@Service
class DefaultObservationIngest implements ObservationIngest {

    private static final Logger log = LoggerFactory.getLogger(DefaultObservationIngest.class);

    /**
     * How far ahead of now an observation may claim to have happened.
     *
     * <p>An observer's clock is not this one's, and a little skew is ordinary. A
     * result claiming to be an hour in the future is not skew - it is a broken
     * clock or a broken observer, and letting it through would write a counter
     * row for a day that has not happened and leave it there when the ribbon
     * moves past it.
     */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

    private final EnvironmentStateRepository states;
    private final EnvironmentDayRepository days;
    private final ObservationBatchRepository batches;
    private final EnvironmentLookup environments;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    DefaultObservationIngest(
            EnvironmentStateRepository states,
            EnvironmentDayRepository days,
            ObservationBatchRepository batches,
            EnvironmentLookup environments,
            ObjectMapper objectMapper,
            Clock clock) {
        this.states = states;
        this.days = days;
        this.batches = batches;
        this.environments = environments;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public IngestResult accept(UUID orgId, ObservationBatch batch) {
        validate(batch);

        // The fast path for a retry. There is still a race - two concurrent
        // deliveries of the same key can both miss here - and the unique
        // constraint below is what actually settles it.
        var existing = batches.findByOrgIdAndIdempotencyKey(orgId, batch.idempotencyKey());
        if (existing.isPresent()) {
            log.debug("Replayed observation batch {}", batch.idempotencyKey());
            return replay(existing.get());
        }

        Instant now = clock.instant();
        Map<UUID, EnvironmentOwner> owners = resolveEnvironments(orgId, batch);

        int applied = 0;
        int ignored = 0;
        Map<UUID, EnvironmentStateEntity> touchedStates = new HashMap<>();
        Map<String, EnvironmentDayEntity> touchedDays = new HashMap<>();

        for (Observation observation : batch.observations()) {
            EnvironmentOwner owner = owners.get(observation.environmentId());
            if (owner == null) {
                // An observer with a slightly stale service list is normal.
                // Failing the batch over one retired environment would discard
                // every other result in it.
                ignored++;
                continue;
            }

            Observation clamped = clampToNow(observation, now);
            applyToState(orgId, owner, clamped, touchedStates);
            applyToDay(orgId, owner, clamped, touchedDays);
            applied++;
        }

        states.saveAll(touchedStates.values());
        days.saveAll(touchedDays.values());

        IngestResult result = new IngestResult(applied, ignored, false);
        recordBatch(orgId, batch, now, result);

        log.debug(
                "Accepted observation batch {} from {}: {} applied, {} ignored",
                batch.idempotencyKey(),
                batch.observerId(),
                applied,
                ignored);
        return result;
    }

    private void applyToState(
            UUID orgId, EnvironmentOwner owner, Observation observation, Map<UUID, EnvironmentStateEntity> touched) {
        EnvironmentStateEntity state = touched.computeIfAbsent(
                observation.environmentId(),
                id -> states.findByOrgIdAndEnvironmentId(orgId, id)
                        .orElseGet(() -> EnvironmentStateEntity.firstObservation(orgId, owner.serviceId(), observation)));

        // firstObservation already applied it; applying again would double-count
        // a failure into consecutiveFailures.
        if (state.getLastProbeAt() != null && !state.getLastProbeAt().equals(observation.observedAt())) {
            state.apply(observation);
        } else if (state.getLastProbeAt() == null) {
            state.apply(observation);
        }
    }

    private void applyToDay(
            UUID orgId, EnvironmentOwner owner, Observation observation, Map<String, EnvironmentDayEntity> touched) {
        LocalDate day = observation.observedAt().atZone(ZoneOffset.UTC).toLocalDate();
        String key = observation.environmentId() + "|" + day;

        EnvironmentDayEntity row = touched.computeIfAbsent(
                key,
                ignored -> days.findByEnvironmentIdAndDay(observation.environmentId(), day)
                        .orElseGet(() -> EnvironmentDayEntity.forDay(
                                orgId, owner.serviceId(), observation.environmentId(), day)));

        row.record(observation);
    }

    /**
     * Records that this key has been applied.
     *
     * <p>The insert is what actually enforces idempotency: two concurrent
     * deliveries of one key both pass the read above, and exactly one survives
     * the primary key. The loser re-reads the winner's response and returns it,
     * which is correct - the batch was applied, once.
     */
    private void recordBatch(UUID orgId, ObservationBatch batch, Instant now, IngestResult result) {
        try {
            batches.saveAndFlush(ObservationBatchEntity.of(
                    orgId, batch.idempotencyKey(), now, batch.observations().size(), write(result)));
        } catch (DataIntegrityViolationException raced) {
            log.debug("Observation batch {} was applied concurrently", batch.idempotencyKey());
            throw new ConcurrentBatchException(batch.idempotencyKey());
        }
    }

    private IngestResult replay(ObservationBatchEntity recorded) {
        try {
            return objectMapper.readValue(recorded.getResponse(), StoredResult.class).toResult();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored batch response is not readable JSON", e);
        }
    }

    private String write(IngestResult result) {
        try {
            return objectMapper.writeValueAsString(new StoredResult(result.applied(), result.ignored()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An ingest result could not be serialized", e);
        }
    }

    /** @param applied and ignored, as first computed. Replayed rather than recomputed. */
    record StoredResult(int applied, int ignored) {
        IngestResult toResult() {
            return new IngestResult(applied, ignored, true);
        }
    }

    /**
     * Which service each reported environment belongs to.
     *
     * <p>One query for the whole batch, and scoped by organization: an observer
     * reporting an environment id belonging to another organization gets it
     * counted as ignored, never applied.
     */
    private Map<UUID, EnvironmentOwner> resolveEnvironments(UUID orgId, ObservationBatch batch) {
        List<UUID> ids = batch.observations().stream()
                .map(Observation::environmentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        // Through catalog's public API rather than a SELECT against its table.
        // It is scoped by organization, so an id belonging to somewhere else is
        // simply absent and gets counted as ignored (ADR 0010).
        Map<UUID, UUID> owners = environments.servicesOwning(orgId, ids);

        Map<UUID, EnvironmentOwner> resolved = new HashMap<>();
        owners.forEach((environmentId, serviceId) -> resolved.put(environmentId, new EnvironmentOwner(serviceId)));
        return resolved;
    }

    /**
     * Pulls an observation back to now if its clock is ahead.
     *
     * <p>Clamped rather than rejected: a mildly skewed observer is still
     * reporting something true about a service, and discarding its results would
     * make the catalog quietly blind rather than visibly wrong.
     */
    private static Observation clampToNow(Observation observation, Instant now) {
        if (!observation.observedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            return observation;
        }
        return new Observation(
                observation.environmentId(),
                now,
                observation.outcome(),
                observation.responseMs(),
                observation.statusCode(),
                observation.detail());
    }

    private static void validate(ObservationBatch batch) {
        List<Violation> violations = new ArrayList<>();

        if (batch.idempotencyKey() == null || !batch.idempotencyKey().matches("[A-Za-z0-9._:-]{8,200}")) {
            violations.add(new Violation(
                    "/idempotencyKey",
                    "format",
                    "8 to 200 characters of letters, digits, dot, underscore, colon or hyphen",
                    batch.idempotencyKey(),
                    "A batch needs an idempotency key. Counters are where a silent double-write does damage:"
                            + " nothing looks broken, the numbers are simply wrong."));
        }
        if (batch.observations() == null || batch.observations().isEmpty()) {
            violations.add(new Violation(
                    "/observations",
                    "minItems",
                    "at least one observation",
                    null,
                    "The batch contains no observations. An observer with nothing to report should not report."));
        } else {
            for (int i = 0; i < batch.observations().size(); i++) {
                Observation observation = batch.observations().get(i);
                if (observation.environmentId() == null) {
                    violations.add(new Violation(
                            "/observations/" + i + "/environmentId",
                            "required",
                            "the id of a declared environment",
                            null,
                            "An observation must say which environment it is about."));
                }
                if (observation.observedAt() == null) {
                    violations.add(new Violation(
                            "/observations/" + i + "/observedAt",
                            "required",
                            "when the probe ran",
                            null,
                            "An observation must say when it happened; it decides which day it counts toward."));
                }
                if (observation.outcome() == null) {
                    violations.add(new Violation(
                            "/observations/" + i + "/outcome",
                            "required",
                            "HEALTHY, UNHEALTHY, TIMEOUT or UNREACHABLE",
                            null,
                            "An observation must say what the probe found."));
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new ValidationFailedException(
                    violations.size() == 1
                            ? "The observation batch was not usable: 1 problem"
                            : "The observation batch was not usable: " + violations.size() + " problems",
                    violations);
        }
    }

    private record EnvironmentOwner(UUID serviceId) {}

    /** Two deliveries of one key raced; the loser should re-read and replay. */
    static class ConcurrentBatchException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String key;

        ConcurrentBatchException(String key) {
            super("Observation batch " + key + " was applied concurrently");
            this.key = key;
        }

        String key() {
            return key;
        }
    }
}
