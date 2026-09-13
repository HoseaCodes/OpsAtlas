package com.ambitiousconcepts.opsatlas.operations.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes rollups and idempotency keys past their retention window.
 *
 * <p>ADR 0009: a table that only grows is a time series wearing a different hat.
 * The bound on storage in that decision is only real because this runs - without
 * it, {@code environment_day} accumulates a row per environment per day forever
 * and the claim that storage is bounded becomes false.
 *
 * <p>An idempotency key only has to outlive the retries of its own request, so
 * it is kept far more briefly than the rollups.
 */
@Component
@ConditionalOnProperty(name = "opsatlas.retention.enabled", havingValue = "true", matchIfMissing = true)
class ObservationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ObservationRetentionJob.class);

    private final EnvironmentDayRepository days;
    private final ObservationBatchRepository batches;
    private final Clock clock;
    private final int retainDays;
    private final int retainKeyHours;

    ObservationRetentionJob(
            EnvironmentDayRepository days,
            ObservationBatchRepository batches,
            Clock clock,
            @Value("${opsatlas.retention.days:35}") int retainDays,
            @Value("${opsatlas.retention.idempotency-key-hours:48}") int retainKeyHours) {
        this.days = days;
        this.batches = batches;
        this.clock = clock;
        this.retainDays = retainDays;
        this.retainKeyHours = retainKeyHours;
    }

    @Scheduled(
            initialDelayString = "${opsatlas.retention.initial-delay:PT2M}",
            fixedDelayString = "${opsatlas.retention.interval:PT6H}")
    @Transactional
    public void prune() {
        // Retained slightly longer than the 30-day ribbon needs, so the ribbon
        // never renders a gap at its oldest edge because a prune landed between
        // two requests.
        LocalDate before = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(retainDays);
        int prunedDays = days.deleteOlderThan(before);
        int prunedKeys = batches.deleteOlderThan(clock.instant().minusSeconds(retainKeyHours * 3600L));

        if (prunedDays > 0 || prunedKeys > 0) {
            log.info("Retention: removed {} daily rollup(s) and {} idempotency key(s)", prunedDays, prunedKeys);
        }
    }
}
