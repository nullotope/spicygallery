package io.github.nullotope.spicygallery.server.embed;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Broadcasts backfill progress (spec §6): same notify-via-SSE / reconcile-via-REST
 * pattern as the import run — a subscriber can never affect the job.
 */
public class BackfillSseHub {

    public record Progress(long done, long total, String current) {}

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void progress(long done, long total, String current) {
        send("progress", new Progress(done, total, current));
    }

    public void done(long done, long total) {
        send("done", new Progress(done, total, null));
    }

    public void failed(String message) {
        send("failed", java.util.Map.of("message", message));
    }

    private void send(String event, Object payload) {
        String json;
        try {
            json = io.github.nullotope.spicygallery.json.SpicyGalleryJson.mapper().writeValueAsString(payload);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("backfill progress serialization failed", e);
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
            } catch (java.io.IOException | IllegalStateException e) {
                emitters.remove(emitter); // broken pipe / completed container — never the job's problem
            }
        }
    }
}
