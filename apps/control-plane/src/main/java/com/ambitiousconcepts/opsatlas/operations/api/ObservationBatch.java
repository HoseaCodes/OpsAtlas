package com.ambitiousconcepts.opsatlas.operations.api;

import java.util.List;

/**
 * A batch of probe results from one observer pass.
 *
 * <p>Batched rather than one request per probe: an observer sweeping fifty
 * environments would otherwise make fifty round trips per pass, and each one
 * would be its own transaction against the same handful of counter rows.
 *
 * @param idempotencyKey identifies this batch. A retry carrying the same key
 *     returns the original result and applies nothing. Counters are exactly
 *     where a silent double-write does damage - nothing looks broken, the
 *     numbers are just wrong - so this is required, not optional.
 * @param observerId which observer produced this, for the audit trail
 */
public record ObservationBatch(String idempotencyKey, String observerId, List<Observation> observations) {}
