package com.ambitiousconcepts.opsatlas.governance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One recorded change.
 *
 * @param actor TODO(auth): 'local-operator' until authentication exists. The
 *     field is real and stored; what it contains is not yet meaningful.
 * @param correlationId ties this entry to the request that caused it and to
 *     every log line that request wrote
 */
public record AuditEntry(
        UUID id,
        Instant occurredAt,
        String actor,
        String action,
        String subjectType,
        UUID subjectId,
        String correlationId,
        JsonNode payload) {}
