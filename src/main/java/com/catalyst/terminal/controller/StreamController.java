package com.catalyst.terminal.controller;

import com.catalyst.terminal.service.SseBroadcastService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final SseBroadcastService sseBroadcastService;

    public StreamController(SseBroadcastService sseBroadcastService) {
        this.sseBroadcastService = sseBroadcastService;
    }

    @GetMapping(value = "/catalysts", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCatalysts() {
        return sseBroadcastService.subscribe();
    }
}

