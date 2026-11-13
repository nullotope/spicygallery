package io.github.nullotope.spicygallery.server.tagging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Hydra tagging client (spec §3.2): {@code GET /info} and {@code POST /classify}.
 *
 * <p>{@link #info()} serves the cached {@link TaggingModelInfo} and never hits the
 * provider while a cache is present; {@link #refresh()} re-fetches and replaces the
 * cache only when the reported model differs (or the cache is empty) — the vocabulary
 * of an unchanged model is stable. Unreachable / non-2xx / timeout surface as
 * {@link IOException}: they are provider faults, but the breaker is the CALLER's
 * decision — info failures must never trip it (§3.2: client retries on next use).
 * An unparseable classify body is a contract error: {@link IllegalArgumentException}.
 */
public final class TaggingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private volatile TaggingModelInfo cache;

    public TaggingClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Cached model info; fetches once when empty, afterwards served without provider calls. */
    public TaggingModelInfo info() throws IOException {
        TaggingModelInfo hit = cache;
        if (hit != null) {
            return hit;
        }
        return refresh();
    }

    /**
     * Re-fetches {@code /info} and replaces the cache when the reported model differs
     * from the cached one (or the cache is empty). Returns the current info either way.
     */
    public TaggingModelInfo refresh() throws IOException {
        HttpResponse<String> response = send(get("/info").build());
        TaggingModelInfo fetched = TaggingModelInfo.fromJson(response.body());
        TaggingModelInfo current = cache;
        if (current == null || !current.model().equals(fetched.model())) {
            cache = fetched;
        }
        return cache;
    }

    /** Classifies one image/video frame; body is the raw bytes (JPEG/PNG). */
    public Map<String, Double> classify(byte[] image, String calibration, String implications)
            throws IOException {
        StringBuilder query = new StringBuilder();
        if (calibration != null) {
            query.append("calibration=").append(encode(calibration));
        }
        if (implications != null) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append("implications=").append(encode(implications));
        }
        HttpResponse<String> response = send(get("/classify" + (query.isEmpty() ? "" : "?" + query))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image))
                .build());
        try {
            return MAPPER.readValue(response.body(), new TypeReference<Map<String, Double>>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("unparseable classify response: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder get(String pathAndQuery) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(timeout)
                .GET();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tagging provider call interrupted", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("tagging provider answered HTTP " + response.statusCode()
                    + " for " + request.uri().getPath());
        }
        return response;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
