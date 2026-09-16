package com.ambitiousconcepts.opsatlas.operations.api;

import java.time.Instant;
import java.util.Optional;

/**
 * What a pipeline says it deployed.
 *
 * @param idempotencyKey what a retried report collapses on. CLAUDE.md §9
 *     requires this for ingestion: a deploy notification retried after a
 *     timeout must not become two deployments and a fictitious rollback
 * @param deployedAt absent means "now", for the common case of a pipeline
 *     reporting as it finishes
 */
public record DeploymentReport(
        String version,
        Optional<String> commitSha,
        Optional<String> deployedBy,
        Optional<Instant> deployedAt,
        String idempotencyKey) {}
