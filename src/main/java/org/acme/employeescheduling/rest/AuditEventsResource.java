package org.acme.employeescheduling.rest;

import java.util.List;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.acme.employeescheduling.persistence.AuditEventEntity;

/** Read-only access to the append-only operational audit trail. */
@Path("/audit-events")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "APPROVER"})
public class AuditEventsResource {
    @GET
    public List<Map<String, Object>> list(@QueryParam("structureId") int structureId,
            @QueryParam("limit") Integer requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit == null ? 200 : requestedLimit, 1), 1000);
        return AuditEventEntity.<AuditEventEntity>find("structureId = ?1 order by id desc", structureId)
                .page(0, limit).list().stream().map(event -> Map.<String, Object>of(
                        "id", event.id,
                        "actor", event.actor,
                        "action", event.action,
                        "entityType", event.entityType,
                        "entityId", event.entityId,
                        "details", event.detailsJson,
                        "createdAt", event.createdAt)).toList();
    }
}
