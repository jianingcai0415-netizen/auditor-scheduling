package org.acme.employeescheduling.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.employeescheduling.persistence.AuditEventEntity;

/** Appends immutable workflow audit events in the caller's transaction. */
@ApplicationScoped
public class AuditTrailService {
    @Inject SecurityIdentity identity;
    @Inject ObjectMapper objectMapper;

    public void record(Integer structureId, String action, String entityType, Object entityId, Object details) {
        AuditEventEntity event = new AuditEventEntity();
        event.structureId = structureId;
        event.actor = identity == null || identity.isAnonymous() ? "system" : identity.getPrincipal().getName();
        event.action = action;
        event.entityType = entityType;
        event.entityId = entityId == null ? "" : String.valueOf(entityId);
        try {
            event.detailsJson = details == null ? "{}" : objectMapper.writeValueAsString(details);
        } catch (Exception ignored) {
            event.detailsJson = "{}";
        }
        event.createdAt = WorkflowSupport.now();
        event.persist();
    }
}
