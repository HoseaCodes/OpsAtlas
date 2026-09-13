package com.ambitiousconcepts.opsatlas.governance.internal;

import com.ambitiousconcepts.opsatlas.governance.api.AuditEntry;
import com.ambitiousconcepts.opsatlas.governance.api.AuditLog;
import com.ambitiousconcepts.opsatlas.shared.Cursor;
import com.ambitiousconcepts.opsatlas.shared.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultAuditLog implements AuditLog {

    private final AuditEventRepository events;
    private final ObjectMapper objectMapper;

    DefaultAuditLog(AuditEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> recent(UUID orgId, String cursor, int limit) {
        // Newest first, so the cursor walks backwards through ids. Row ids are
        // UUIDv7 and therefore time-ordered, so "older than this one" is
        // id < cursor - the same keyset trick the catalog uses, reversed.
        Limit fetch = Limit.of(limit + 1);
        List<AuditEventEntity> rows = cursor == null
                ? events.findByOrgIdOrderByOccurredAtDescIdDesc(orgId, fetch)
                : events.findByOrgIdAndIdLessThanOrderByOccurredAtDescIdDesc(orgId, Cursor.decode(cursor), fetch);

        boolean hasMore = rows.size() > limit;
        List<AuditEventEntity> page = hasMore ? rows.subList(0, limit) : rows;

        List<AuditEntry> items = page.stream().map(this::toEntry).toList();
        String nextCursor = hasMore ? Cursor.encode(page.get(page.size() - 1).getId()) : null;

        return PageResponse.of(items, nextCursor);
    }

    private AuditEntry toEntry(AuditEventEntity entity) {
        return new AuditEntry(
                entity.getId(),
                entity.getOccurredAt(),
                entity.getActor(),
                entity.getAction(),
                entity.getSubjectType(),
                entity.getSubjectId(),
                entity.getCorrelationId(),
                readPayload(entity));
    }

    private com.fasterxml.jackson.databind.JsonNode readPayload(AuditEventEntity entity) {
        try {
            return objectMapper.readTree(entity.getPayload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored audit payload for event " + entity.getId() + " is not JSON", e);
        }
    }
}
