package io.github.nullotope.spicygallery.server.tagging;

import io.github.nullotope.spicygallery.json.SpicyGalleryJson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Broadcasts bulk-tagging progress (spec §6): same notify-via-SSE / reconcile-via-REST
 * pattern as the import run and the embedding backfill — a subscriber can never affect
 * the job. Progress events carry the per-entry error count alongside done/total so the
 * UI can show partial failures while the run is still going.
 */
public class TaggingSseHub {

    public record Progress(long done, long total, String current, long errors) {}

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void progress(long done, long total, String current, long errors) {
        send("progress", new Progress(done, total, current, errors));
    }

    public void done(long done, long total, long errors) {
        send("done", new Progress(done, total, null, errors));
    }

    public void failed(String message) {
        send("failed", Map.of("message", message));
    }

    private void send(String event, Object payload) {
        String json;
        try {
            json = SpicyGalleryJson.mapper().writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("tagging progress serialization failed", e);
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter); // broken pipe / completed container — never the job's problem
            }
        }
    }
}
