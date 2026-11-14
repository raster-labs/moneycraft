package com.raster.moneycraft.foundation.events;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SseBroadcasterService {

    private static final long SSE_TIMEOUT_MS = 1_800_000L;

    private final ObjectMapper objectMapper;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseBroadcasterService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter registerClient() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(ex -> emitters.remove(emitter));

        OperationalEvent initialHeartbeat = new OperationalEvent(
            UUID.randomUUID().toString(),
            "HEARTBEAT",
            null,
            Instant.now(),
            Map.of("status", "CONNECTED")
        );
        sendToSingle(emitter, initialHeartbeat);
        return emitter;
    }

    public void broadcast(String type, UUID companyId, Map<String, Object> payload) {
        OperationalEvent event = new OperationalEvent(
            UUID.randomUUID().toString(),
            type,
            companyId,
            Instant.now(),
            payload == null ? Map.of() : payload
        );
        for (SseEmitter emitter : emitters) {
            sendToSingle(emitter, event);
        }
    }

    @Scheduled(fixedDelay = 25_000L, initialDelay = 25_000L)
    public void emitScheduledHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        broadcast("HEARTBEAT", null, Map.of("activeSubscribers", emitters.size()));
    }

    private void sendToSingle(SseEmitter emitter, OperationalEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(
                SseEmitter.event()
                    .id(event.eventId())
                    .name("message")
                    .data(json, MediaType.APPLICATION_JSON)
            );
        } catch (JacksonException | IOException | IllegalStateException ex) {
            emitters.remove(emitter);
        }
    }
}
