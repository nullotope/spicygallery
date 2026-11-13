package io.github.nullotope.spicygallery.server.importjob;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Broadcasts import progress to SSE subscribers (spec §8). SSE notifies, REST
 * reconciles — {@code GET /api/imports/last} stays the source of truth.
 */
@org.springframework.stereotype.Component
public class ImportSseHub {

    // package-private add() is the test seam; register() is the production entry
    private final java.util.List<SseEmitter> emitters = new java.util.concurrent.CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        add(emitter);
        return emitter;
    }

    void add(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
    }

    public void progress(io.github.nullotope.spicygallery.domain.ImportStats s) {
        send("progress", s);
    }

    public void done(io.github.nullotope.spicygallery.domain.ImportResult r) {
        send("done", r);
    }

    public void failed(String message) {
        send("failed", java.util.Map.of("message", message));
    }

    private void send(String event, Object payload) {
        String json;
        try {
            json = io.github.nullotope.spicygallery.json.SpicyGalleryJson.mapper().writeValueAsString(payload);
        } catch (java.io.IOException e) {
            throw new UncheckedSerializationException(e);
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event)
                        .data(json, org.springframework.http.MediaType.APPLICATION_JSON));
            } catch (java.io.IOException | IllegalStateException e) {
                // IOException: broken pipe. IllegalStateException: the container already
                // completed this emitter (client disconnected) — an unchecked throw that
                // used to unwind into the pipeline and abort the whole run (review C2).
                // The run's fate must never depend on a subscriber.
                emitters.remove(emitter);
            }
        }
    }

    static final class UncheckedSerializationException extends RuntimeException {
        UncheckedSerializationException(Throwable cause) {
            super(cause);
        }
    }
}
