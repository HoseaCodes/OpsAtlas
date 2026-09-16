package com.ambitiousconcepts.opsatlas.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A version somebody reported deploying to an environment.
 *
 * <p>Reported, not discovered. OpsAtlas does not read a registry, does not ask a
 * cluster and does not verify the claim - a row here says a pipeline said a
 * version went out, which is the only thing this system can honestly know until
 * something exposes a running version to probe (ADR 0017).
 *
 * @param version free text, because a version is a semver, a date stamp or a
 *     branch name depending on whose pipeline is reporting
 * @param commitSha null when the reporter could not name one
 * @param deployedBy who or what shipped it, null when unstated. Never inferred
 * @param deployedAt when the deploy happened, which is not when it was reported
 */
public record Deployment(
        UUID id,
        UUID environmentId,
        String environmentName,
        String version,
        @Schema(nullable = true) String commitSha,
        @Schema(nullable = true) String deployedBy,
        Instant deployedAt) {}
