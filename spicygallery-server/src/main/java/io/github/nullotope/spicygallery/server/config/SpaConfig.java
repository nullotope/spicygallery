package io.github.nullotope.spicygallery.server.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Explicitly serves the built webclient SPA's static files from the classpath.
 *
 * <p>Maps {@code /**} to the SPA's static locations and reads them from
 * {@code spring.web.resources.static-locations} (the same property Boot's own
 * auto-configured resource handler reads). The default is Boot's full set plus
 * {@code classpath:/spa/} — where the resources-plugin copy puts the built SPA —
 * so this handler is behaviourally identical to Boot's and never shadows it.
 * Registering it explicitly (rather than relying purely on auto-config) makes
 * the SPA's serving location a visible, intentional part of the server config
 * and keeps it working even if the auto-config's {@code add-mappings} is
 * switched off.</p>
 *
 * <p>In the production jar the frontend build is copied into
 * {@code classpath:/spa/} by the resources-plugin step, so {@code index.html} +
 * {@code /assets/**} come straight from the classpath. The routing tests override
 * the property to a committed stub ({@code classpath:/spa-test/}) so they pass
 * without a Node toolchain.</p>
 *
 * <p>The history-mode <em>forward</em> of unknown dotless routes to
 * {@code index.html} (deep-link refresh) lives in the dedicated,
 * highest-precedence advice {@link SpaHistoryModeForwarder}.</p>
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    /** Boot's default static locations + the SPA build output copied into the jar. */
    static final String DEFAULT_STATIC_LOCATIONS =
            "classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,"
                    + "classpath:/public/,classpath:/spa/";

    /**
     * Comma-separated classpath locations the SPA's static files come from.
     * Overridable via {@code spring.web.resources.static-locations} (the tests
     * point this at the committed {@code spa-test} stub).
     */
    @Value("${spring.web.resources.static-locations:" + DEFAULT_STATIC_LOCATIONS + "}")
    private String staticLocations;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(splitLocations(staticLocations));
    }

    /** Split the comma-separated locations, trimming blanks and dropping empties. */
    private String[] splitLocations(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
