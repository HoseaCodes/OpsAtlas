package com.ambitiousconcepts.opsatlas.governance.api;

import java.util.Map;
import java.util.UUID;

/**
 * Recording what happened.
 *
 * <p>Called from inside the transaction that made the change, so an audit entry
 * cannot survive a rolled-back change and a committed change cannot go
 * unrecorded (CLAUDE.md section 8).
 *
 * <p>Entries are append-only. In slice one that is enforced by this being the
 * only way to write one and there being no way to update or delete; the
 * database-level revoke is in {@code docs/roadmap.md} and is not done, so the
 * table must not be called immutable yet.
 */
public interface AuditRecorder {

    /**
     * @param action dotted, lowercase, e.g. {@code service.registered}
     * @param payload what changed; must not contain secrets, and is stored as-is
     */
    void record(UUID orgId, String action, String subjectType, UUID subjectId, Map<String, Object> payload);
}
