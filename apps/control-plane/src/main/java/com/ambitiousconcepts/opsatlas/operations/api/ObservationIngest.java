package com.ambitiousconcepts.opsatlas.operations.api;

import java.util.UUID;

/** Accepting what an observer reports. */
public interface ObservationIngest {

    /**
     * Fold a batch of probe results into the rolled-up state.
     *
     * <p>Idempotent by {@link ObservationBatch#idempotencyKey()}: a replayed
     * batch returns the original result and applies nothing.
     */
    IngestResult accept(UUID orgId, ObservationBatch batch);

    /**
     * @param applied how many observations were folded in
     * @param ignored observations for environments that do not exist in this
     *     organization. Reported rather than rejected: an observer holding a
     *     slightly stale service list is normal, and failing the whole batch
     *     over one retired environment would lose every other result in it.
     * @param replayed true when this key had already been accepted, so nothing
     *     was applied
     */
    record IngestResult(int applied, int ignored, boolean replayed) {}
}
