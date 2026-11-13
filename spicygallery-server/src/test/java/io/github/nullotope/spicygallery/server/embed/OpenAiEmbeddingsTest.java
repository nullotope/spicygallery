package io.github.nullotope.spicygallery.server.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OpenAI-compatible embeddings client (spec §3.2): URL, auth, wire shape, ordering. */
class OpenAiEmbeddingsTest {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> methods = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    /** Canned: two 2-dim vectors, returned regardless of input. */
    private static final String RESPONSE =
            "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]},{\"index\":1,\"embedding\":[0.3,0.4]}]}";

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            methods.add(exchange.getRequestMethod());
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private OpenAiEmbeddings client(String apiKey) {
        return new OpenAiEmbeddings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "embeddinggemma-2", apiKey, java.time.Duration.ofSeconds(10));
    }

    @Test
    void sends_model_and_text_input_to_configured_url() throws IOException {
        List<float[]> out = client(null)
                .embed(List.of(new EmbeddingProvider.EmbeddingInput("hello world", null, null)));
        assertEquals("/v1/embeddings", paths.get(0));
        assertEquals("POST", methods.get(0));
        assertTrue(lastBody.get().contains("\"model\":\"embeddinggemma-2\""));
        assertTrue(lastBody.get().contains("\"input\":[\"hello world\"]"));
        assertEquals(2, out.size());
    }

    @Test
    void image_input_is_data_uri_with_mime() throws IOException {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2};
        client(null).embed(List.of(new EmbeddingProvider.EmbeddingInput(null, jpeg, "image/jpeg")));
        String body = lastBody.get();
        String expected = "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(jpeg);
        // pinned shape (Task 1 probe): content-parts wrapper, exactly as llama.cpp accepts
        assertTrue(body.contains("\"type\":\"content\""), body);
        assertTrue(body.contains("\"type\":\"image_url\""), body);
        assertTrue(body.contains(expected), body);
    }

    @Test
    void bearer_header_only_when_api_key_set() throws IOException {
        client("secret-key").embed(List.of(new EmbeddingProvider.EmbeddingInput("a", null, null)));
        assertEquals("Bearer secret-key", authHeaders.get(0));
        client(null).embed(List.of(new EmbeddingProvider.EmbeddingInput("b", null, null)));
        assertFalse(authHeaders.get(1) != null && authHeaders.get(1).startsWith("Bearer"),
                "no Authorization header without an api key");
    }

    @Test
    void returns_vectors_in_request_order() throws IOException {
        List<float[]> out = client(null).embed(List.of(
                new EmbeddingProvider.EmbeddingInput("a", null, null),
                new EmbeddingProvider.EmbeddingInput("b", null, null)));
        assertArrayEquals(new float[] {0.1f, 0.2f}, out.get(0), 1e-6f);
        assertArrayEquals(new float[] {0.3f, 0.4f}, out.get(1), 1e-6f);
    }

    @Test
    void non_200_raises_IOException() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        var bad = new OpenAiEmbeddings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "m", null, java.time.Duration.ofSeconds(5));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> bad.embed(
                List.of(new EmbeddingProvider.EmbeddingInput("x", null, null))));
    }
}
