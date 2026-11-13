package io.github.nullotope.spicygallery.server.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The SPA's history-mode fallback (spec §9): turn a "no such static resource" for a
 * client-side <em>route</em> into a forward to {@code index.html}, so a browser
 * refresh on a deep link (e.g. {@code /entries/6758958}) lands back in the
 * single-page app instead of a 404.
 *
 * <h3>The dot rule (routes vs. assets)</h3>
 * A path that does not resolve to a static resource is treated as a route — and
 * forwarded to {@code index.html} — only when it has <b>no dot in any segment</b>.
 * Vite/Quasar emit hashed asset filenames ({@code /assets/index-7yNP8oe5.js}), so
 * any real asset carries a dot; a route like {@code /entries/42} does not. A dotted
 * path that doesn't exist is therefore a missing <em>asset</em> (404); a dotless
 * path is a missing <em>route</em> (the SPA shell).
 *
 * <h3>The {@code /api} guard</h3>
 * The REST API lives under {@code /api/**}; those paths are never forwarded to
 * the SPA: an unmapped {@code /api/...} path stays a 404 with no SPA body.
 *
 * <h3>Why a separate, highest-precedence advice</h3>
 * {@code NoResourceFoundException} is raised by the static
 * {@code ResourceHttpRequestHandler}, not a controller method. Spring consults
 * {@code @ControllerAdvice} beans for it, and {@code GlobalExceptionHandler}
 * (extending {@code ResponseEntityExceptionHandler}) also handles it — advice
 * beans are consulted in {@code @Order} order, so this advice is
 * {@code @Order(HIGHEST_PRECEDENCE)} to win: dotless non-API routes get the
 * forward, everything else an explicit bodyless 404 — never rethrown, which
 * would fall through to the lower-precedence handler.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class SpaHistoryModeForwarder {

    /** The API prefix that must never be forwarded to the SPA. */
    private static final String API_PREFIX = "/api/";

    /**
     * Dotless non-API path the static handler couldn't serve → forward to the
     * SPA shell. Dotted (missing asset) or {@code /api/**} (missing API path) →
     * an explicit 404, so the lower-precedence handler never makes it a 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object noResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        if (isSpaRoute(request.getRequestURI())) {
            // forward: re-dispatch to /index.html, which the static handler serves
            // as the SPA shell. A ModelAndView (not a bare String) is needed so the
            // forward is treated as a view name, not serialized.
            return new ModelAndView("forward:/index.html");
        }
        // A clean 404 with no body: explicit, so the lower-precedence handler
        // (which would rewrite this) never sees it.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /** A dotless, non-API path is a client-side route → serve the SPA shell. */
    private boolean isSpaRoute(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // "/" is served directly (Boot's welcome page / the static handler),
        // not a missing resource; guard anyway so it is never a 404.
        if ("/".equals(path)) {
            return true;
        }
        if ("/api".equals(path) || path.startsWith(API_PREFIX)) {
            return false;
        }
        return !path.contains(".");
    }
}
