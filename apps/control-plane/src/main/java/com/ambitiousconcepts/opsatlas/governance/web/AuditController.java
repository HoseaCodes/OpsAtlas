package com.ambitiousconcepts.opsatlas.governance.web;

import com.ambitiousconcepts.opsatlas.governance.api.AuditEntry;
import com.ambitiousconcepts.opsatlas.governance.api.AuditLog;
import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit log, newest first, cursor-paged like every other collection. */
@RestController
@RequestMapping("/api/v1/audit-events")
class AuditController {

    private static final int DEFAULT_LIMIT = 50;

    private final AuditLog auditLog;
    private final CurrentPrincipal currentPrincipal;

    AuditController(AuditLog auditLog, CurrentPrincipal currentPrincipal) {
        this.auditLog = auditLog;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    PageResponse<AuditEntry> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(200) int limit) {
        return auditLog.recent(currentPrincipal.get().orgId(), cursor, limit);
    }
}
