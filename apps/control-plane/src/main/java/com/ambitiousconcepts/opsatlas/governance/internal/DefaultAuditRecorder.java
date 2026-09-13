package com.ambitiousconcepts.opsatlas.governance.internal;

import com.ambitiousconcepts.opsatlas.governance.api.AuditRecorder;
import com.ambitiousconcepts.opsatlas.identity.api.CurrentPrincipal;
import com.ambitiousconcepts.opsatlas.shared.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class DefaultAuditRecorder implements AuditRecorder {

    private final AuditEventRepository events;
    private final CurrentPrincipal currentPrincipal;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    DefaultAuditRecorder(
            AuditEventRepository events,
            CurrentPrincipal currentPrincipal,
            ObjectMapper objectMapper,
            Clock clock) {
        this.events = events;
        this.currentPrincipal = currentPrincipal;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * No transaction of its own, deliberately. This joins the caller's
     * transaction so an audit entry cannot outlive a rolled-back change, and a
     * committed change cannot go unrecorded.
     */
    @Override
    public void record(UUID orgId, String action, String subjectType, UUID subjectId, Map<String, Object> payload) {
        events.save(AuditEventEntity.of(
                orgId,
                clock.instant(),
                // TODO(auth): the stub principal's subject. Becomes a real
                // identity the moment SeededOrgPrincipalResolver is replaced.
                currentPrincipal.get().subject(),
                action,
                subjectType,
                subjectId,
                CorrelationIdFilter.current(),
                writePayload(payload)));
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "An audit payload must be serializable to JSON. Action was not recorded.", e);
        }
    }
}
