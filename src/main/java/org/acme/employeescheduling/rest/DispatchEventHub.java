package org.acme.employeescheduling.rest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/** In-process SSE fan-out for live employee and schedule-plan changes. */
@ApplicationScoped
public class DispatchEventHub {
    private final Set<SseEventSink> sinks = ConcurrentHashMap.newKeySet();
    @Inject ObjectMapper objectMapper;
    private volatile Sse sse;

    void register(SseEventSink sink, Sse sse) {
        this.sse = sse;
        sinks.add(sink);
    }

    public void publish(String name, Object payload) {
        Sse localSse = sse;
        if (localSse == null || sinks.isEmpty()) return;
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return;
        }
        for (SseEventSink sink : sinks) {
            if (sink.isClosed()) {
                sinks.remove(sink);
                continue;
            }
            sink.send(localSse.newEventBuilder().name(name).mediaType(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                    .data(String.class, json).build())
                    .whenComplete((ignored, failure) -> { if (failure != null) sinks.remove(sink); });
        }
    }
}
