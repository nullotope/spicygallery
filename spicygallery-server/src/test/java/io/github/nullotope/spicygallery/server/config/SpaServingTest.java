package io.github.nullotope.spicygallery.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import io.github.nullotope.spicygallery.server.PostgresBase;

/**
 * Jar-level regression net: the REAL built SPA (spicygallery-webclient/dist/spa,
 * copied to {@code classpath:/spa/} by the resources-plugin step) must be served
 * at {@code /} and on deep-link refreshes.
 *
 * <p>Gated on BOTH the dist existing and a previous full build having copied it
 * into {@code target/classes/spa}: the copy runs at {@code prepare-package},
 * i.e. <em>after</em> this module's test phase, so the first-ever
 * {@code mvn install} runs this test skipped and populates the copy; every later
 * build asserts it. A backend-only build (no dist, {@code -DskipFrontendBuild})
 * skips it — the build-order rule. The authoritative jar check is the
 * acceptance task's {@code java -jar} smoke.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("io.github.nullotope.spicygallery.server.config.SpaServingTest#realSpaOnTestClasspath")
class SpaServingTest extends PostgresBase {

    static boolean realSpaOnTestClasspath() {
        return Files.exists(Path.of("../spicygallery-webclient/dist/spa/index.html"))
                && Files.exists(Path.of("target/classes/spa/index.html"));
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void realBuiltSpaIsServedAtRoot() {
        ResponseEntity<String> res = http.getForEntity("/", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("q-app")
                .contains("SpicyGallery");
    }

    @Test
    void realSpaServesDeepLinkRefresh() {
        ResponseEntity<String> res = http.getForEntity("/entries/6758958", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("q-app");
    }
}
