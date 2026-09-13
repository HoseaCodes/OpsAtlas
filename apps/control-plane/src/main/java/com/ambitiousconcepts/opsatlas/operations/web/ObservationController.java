package com.ambitiousconcepts.opsatlas.operations.web;

import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.operations.api.ObservationBatch;
import com.ambitiousconcepts.opsatlas.operations.api.ObservationIngest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where an observer reports what it found.
 *
 * <p>The only write endpoint in this system that is meant for a machine rather
 * than a person, which is why it is batched and idempotent rather than
 * conversational.
 *
 * <p><strong>TODO(auth): this is unauthenticated.</strong> Anything that can
 * reach it can write observations for any environment in the seeded
 * organization. That is acceptable only because nothing authenticates any
 * endpoint yet (ADR 0003) and the control plane runs on a developer's machine -
 * it is not a gap peculiar to this endpoint, but it is the one where it would
 * matter most, because fabricated observations are hard to spot after the fact.
 * The README says so.
 */
@RestController
@RequestMapping("/api/v1/observations")
class ObservationController {

    private final ObservationIngest ingest;
    private final CurrentPrincipal currentPrincipal;

    ObservationController(ObservationIngest ingest, CurrentPrincipal currentPrincipal) {
        this.ingest = ingest;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 202 rather than 201: nothing was created that the caller can go and fetch.
     * The results were folded into counters, and the body says how many landed.
     *
     * <p>A replayed batch is also 202 with the original counts, so a retrying
     * observer cannot tell - and does not need to tell - that it retried.
     */
    @PostMapping
    ResponseEntity<ObservationIngest.IngestResult> accept(@RequestBody ObservationBatch batch) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ingest.accept(currentPrincipal.get().orgId(), batch));
    }
}
