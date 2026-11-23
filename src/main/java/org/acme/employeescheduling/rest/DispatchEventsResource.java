package org.acme.employeescheduling.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/** Authenticated real-time event stream used by the planning UI. */
@Path("/events/dispatch")
@RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER", "VIEWER"})
public class DispatchEventsResource {
    @Inject DispatchEventHub hub;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void events(@Context SseEventSink sink, @Context Sse sse) {
        hub.register(sink, sse);
    }
}
