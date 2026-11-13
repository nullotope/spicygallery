package io.github.nullotope.spicygallery.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import io.github.nullotope.spicygallery.server.PostgresBase;

/**
 * The SPA routing contract (spec §9): {@code /} serves the shell, history-mode
 * deep links (dotless, non-API) serve the shell on refresh, the REST API stays
 * JSON-only, and missing dotted assets 404 like normal files.
 *
 * <p>Runs against a committed stub ({@code classpath:/spa-test/}) so a plain
 * {@code mvn -pl spicygallery-server test} passes on a fresh clone without a
 * Node toolchain — the REAL built SPA is asserted by {@link SpaServingTest}
 * (gated on a completed full build) and the acceptance task's {@code java -jar}
 * smoke. MockMvc cannot execute forward views, so a real random-port server
 * proves the deep-link forward returns actual bytes.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.web.resources.static-locations=classpath:/spa-test/")
class SpaConfigTest extends PostgresBase {

    @Autowired
    TestRestTemplate http;

    @Test
    void servesSpaShellAtRoot() {
        ResponseEntity<String> res = http.getForEntity("/", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("q-app")
                .contains("SpicyGallery");
    }

    @Test
    void keepsApiAsJsonAndNeverServesSpaForApiPaths() {
        ResponseEntity<String> entries = http.getForEntity("/api/entries", String.class);
        assertThat(entries.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entries.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(entries.getBody())
                .isNotBlank()
                .doesNotContain("q-app");

        // an unmapped API path: 404, and NOT the SPA shell
        ResponseEntity<String> unknown = http.getForEntity("/api/no-such-endpoint", String.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody() == null || !unknown.getBody().contains("q-app")).isTrue();
    }

    @Test
    void forwardsUnknownDotlessRouteToSpaShell() {
        // history-mode deep link: /entries/:id (spec §9) must serve the shell
        ResponseEntity<String> deep = http.getForEntity("/entries/42", String.class);
        assertThat(deep.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deep.getBody()).contains("q-app");

        // a second route, to prove the forward is a catch-all, not a single path
        ResponseEntity<String> other = http.getForEntity("/settings", String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(other.getBody()).contains("q-app");
    }

    @Test
    void servesKnownStaticAssetAsFile() {
        ResponseEntity<String> asset = http.getForEntity("/app.js", String.class);
        assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asset.getBody())
                .contains("__SPA_TEST_ASSET__")
                .doesNotContain("q-app");
    }

    @Test
    void returns404ForMissingDotPathWithoutTreatingItAsRoute() {
        // hashed Vite assets always carry a dot → a missing dotted path is a
        // missing ASSET (404), never a deep link, and never a 500 from a
        // lower-precedence catch-all
        ResponseEntity<String> missing = http.getForEntity("/assets/nope.js", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String body = missing.getBody();
        assertThat(body == null || !body.contains("q-app")).isTrue();
    }
}
