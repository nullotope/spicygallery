package io.github.nullotope.spicygallery.server.tagging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Hydra client (spec §3.2): /info cache + model-change invalidation, /classify wire shape. */
class TaggingClientTest {

    private HttpServer server;
    private final AtomicInteger infoHits = new AtomicInteger();
    private final AtomicReference<String> servedModel = new AtomicReference<>("Hydra 3.5");

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/info", exchange -> {
            infoHits.incrementAndGet();
            byte[] body = ("{\"model\":\"" + servedModel.get() + "\",\"extensions\":[],\"image_config\":{},"
                    + "\"labels\":{\"red_hair\":{\"category\":\"general\",\"subcategory\":null,\"implies\":[]}}}")
                    .getBytes(StandardCharsets.UTF_8);
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

    private TaggingClient client() {
        return new TaggingClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
    }

    @Test
    void classify_posts_raw_bytes_and_parses_scores() throws Exception {
        var seen = new AtomicReference<String>();
        server.createContext("/classify", ex -> {
            seen.set(ex.getRequestURI().getQuery() + "|" + new String(ex.getRequestBody().readAllBytes()));
            byte[] body = "{\"red_hair\":0.91,\"smile\":0.42}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        var client = client();
        var scores = client.classify("img".getBytes(), "f2.0@0.1", "inherit");
        assertEquals(0.91, scores.get("red_hair"), 1e-9);
        assertEquals(0.42, scores.get("smile"), 1e-9);
        var decoded = URLDecoder.decode(seen.get(), StandardCharsets.UTF_8);
        assertEquals("calibration=f2.0@0.1&implications=inherit|img", decoded);
    }

    @Test
    void info_is_cached_until_the_model_name_changes() throws Exception {
        var client = client();
        assertEquals("Hydra 3.5", client.info().model());
        assertEquals("Hydra 3.5", client.info().model()); // served from cache
        assertEquals(1, infoHits.get(), "second info() must not hit the provider again");
        servedModel.set("Hydra 4.0");
        assertEquals("Hydra 4.0", client.refresh().model()); // model differs → cache replaced
        assertEquals(2, infoHits.get());
        assertEquals("Hydra 4.0", client.info().model()); // cache now serves the new model
        assertEquals(2, infoHits.get());
    }

    @Test
    void classify_non_200_is_IOException() {
        server.createContext("/classify-fail", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        // point the client at the failing path via the classify endpoint of a fresh base URL
        var failing = new TaggingClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/classify-fail", Duration.ofSeconds(5));
        assertThrows(IOException.class, () -> failing.classify("img".getBytes(), "f1.0@0.1", "inherit"));
    }

    @Test
    void classify_unparseable_body_is_IllegalArgumentException() {
        server.createContext("/classify", ex -> {
            byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        assertThrows(IllegalArgumentException.class,
                () -> client().classify("img".getBytes(), "f1.0@0.1", "inherit"));
    }

    @Test
    void info_unreachable_throws_IOException() {
        // port 1: nothing listens — the caller decides what to do (never trips the breaker)
        var dead = new TaggingClient("http://127.0.0.1:1", Duration.ofSeconds(2));
        assertThrows(IOException.class, dead::info);
    }
}
