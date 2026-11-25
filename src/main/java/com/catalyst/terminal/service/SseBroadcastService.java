package com.catalyst.terminal.service;

import com.catalyst.terminal.model.CatalystFilingRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseBroadcastService {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("status", "STREAM_CONNECTED")));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void broadcastFiling(CatalystFilingRecord record) {
        sendToAll("filing", record);
    }

    public void broadcastEvent(String eventName, Object payload) {
        sendToAll(eventName, payload);
    }

    @Scheduled(fixedRate = 25000)
    public void sendHeartbeat() {
        if (!emitters.isEmpty()) {
            sendToAll("heartbeat", Map.of("ts", System.currentTimeMillis()));
        }
    }

    public int getActiveSubscribers() {
        return emitters.size();
    }

    private void sendToAll(String eventName, Object data) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
        }
    }
}

