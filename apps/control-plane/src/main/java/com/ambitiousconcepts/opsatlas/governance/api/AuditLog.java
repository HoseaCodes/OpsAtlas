package com.ambitiousconcepts.opsatlas.governance.api;

import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import java.util.UUID;

/** Reading the audit log. Writing is {@link AuditRecorder}; the two are separate on purpose. */
public interface AuditLog {

    /** Newest first, cursor-paged. */
    PageResponse<AuditEntry> recent(UUID orgId, String cursor, int limit);
}
