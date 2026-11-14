package com.raster.moneycraft.foundation.events;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/events")
public class EventsStreamController {

    private final SseBroadcasterService broadcasterService;

    public EventsStreamController(SseBroadcasterService broadcasterService) {
        this.broadcasterService = broadcasterService;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOperationalEvents() {
        return broadcasterService.registerClient();
    }
}
