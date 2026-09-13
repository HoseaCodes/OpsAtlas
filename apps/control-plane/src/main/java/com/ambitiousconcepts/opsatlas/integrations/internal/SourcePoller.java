package com.ambitiousconcepts.opsatlas.integrations.internal;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls watched sources on a schedule.
 *
 * <p>ADR 0008: OpsAtlas polls rather than registering webhooks, so that it never
 * holds write access to a monitored repository. The interval is the whole of the
 * freshness guarantee, and it trades directly against rate limit - GitHub allows
 * 60 unauthenticated requests an hour per IP, so at five minutes roughly five
 * sources saturate it.
 *
 * <p>Sources are taken oldest-attempt-first, so one that has never been tried
 * goes before one polled a minute ago, and a batch cap keeps a single pass
 * bounded no matter how many sources exist. A source not reached in this pass is
 * simply first in line for the next one.
 *
 * <p>Sequential, not concurrent. Every request in a pass goes to the same
 * provider against the same rate limit, so parallelism would buy latency on a
 * background job while making it easier to exhaust the quota that limits it.
 *
 * <p>Disabled in tests ({@code opsatlas.sync.enabled=false}), because a timer
 * firing underneath an assertion is a flake generator.
 */
@Component
@ConditionalOnProperty(name = "opsatlas.sync.enabled", havingValue = "true", matchIfMissing = true)
class SourcePoller {

    private static final Logger log = LoggerFactory.getLogger(SourcePoller.class);

    private final SourceRepository sources;
    private final SourceSyncService sync;
    private final int batchSize;

    SourcePoller(
            SourceRepository sources,
            SourceSyncService sync,
            @Value("${opsatlas.sync.batch-size:25}") int batchSize) {
        this.sources = sources;
        this.sync = sync;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${opsatlas.sync.initial-delay:PT30S}",
            fixedDelayString = "${opsatlas.sync.interval:PT5M}")
    void pollDueSources() {
        List<SourceEntity> due = sources.findDueForSync(Limit.of(batchSize));
        if (due.isEmpty()) {
            return;
        }

        log.debug("Polling {} source(s)", due.size());
        int failed = 0;

        for (SourceEntity source : due) {
            try {
                // Each sync manages its own transactions. One repository with a
                // broken manifest must not discard the work done for the rest of
                // the pass.
                var view = sync.sync(source.getOrgId(), source.getId());
                if (!view.healthy()) {
                    failed++;
                }
            } catch (RuntimeException e) {
                // A sync that throws rather than returning a failed outcome is a
                // defect in this control plane. It must not stop the pass, and
                // it must not be silent.
                failed++;
                log.error(
                        "Sync threw for source {} ({}/{}). This is a control-plane defect, not a repository problem.",
                        source.getId(),
                        source.getRepository(),
                        source.getPath(),
                        e);
            }
        }

        if (failed > 0) {
            log.info("Poll finished: {} of {} source(s) did not sync cleanly", failed, due.size());
        }
    }
}
