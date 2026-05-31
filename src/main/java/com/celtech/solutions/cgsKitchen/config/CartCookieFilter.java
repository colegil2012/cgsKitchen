package com.celtech.solutions.cgsKitchen.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;
import java.util.UUID;

/**
 * Issues a stable, opaque cart-cookie to every browser that visits the
 * site and exposes its value as a request attribute so downstream
 * handlers can resolve "which cart is this visitor's" without parsing
 * cookies themselves.
 *
 * <p>The cookie is set even for signed-in users — the design treats the
 * cookie as a durable browser identifier; the cart row behind it is
 * what comes and goes. After a guest signs in, their guest cart row
 * is merged + deleted, but the cookie keeps living. On logout (or a
 * subsequent guest session), the next read will find no row and a
 * fresh guest cart will be created against the same cookie id.
 *
 * <p>Paths skipped:
 * <ul>
 *   <li>{@code /api/**} and {@code /webhooks/**} — server-to-server,
 *       don't need carts</li>
 *   <li>{@code /actuator/**} — health/info endpoints</li>
 *   <li>Static resources — no point setting cookies on every CSS file</li>
 * </ul>
 *
 * <p>Filter is registered with {@link Ordered#HIGHEST_PRECEDENCE} so it
 * runs before Spring Security; that way the request attribute is
 * populated regardless of auth state.
 */
@Slf4j
@Configuration
public class CartCookieFilter {

    /** Cookie name. Namespaced to avoid collision with anything else. */
    public static final String COOKIE_NAME = "cgsk_cart";

    /** Request attribute key. Downstream code reads via request.getAttribute(...). */
    public static final String REQUEST_ATTR = "cgskCartCookieId";

    /** Cookie lifetime in seconds — 30 days. Matches PersistentCart guest TTL. */
    private static final int MAX_AGE_SECONDS = 30 * 24 * 60 * 60;

    private final AppProperties props;

    public CartCookieFilter(AppProperties props) {
        this.props = props;
    }

    @Bean
    public FilterRegistrationBean<Filter> cartCookieFilterRegistration() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl(props));
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("cartCookieFilter");
        return reg;
    }

    /** Actual filter implementation — declared inline so it isn't a bean itself. */
    static class Impl implements Filter {

        private final AppProperties props;

        Impl(AppProperties props) {
            this.props = props;
        }

        @Override
        public void doFilter(ServletRequest sreq, ServletResponse sres, FilterChain chain)
                throws IOException, ServletException {

            HttpServletRequest req = (HttpServletRequest) sreq;
            HttpServletResponse res = (HttpServletResponse) sres;

            if (shouldSkip(req)) {
                chain.doFilter(req, res);
                return;
            }

            String existing = readCookie(req);
            String cookieId;

            if (existing != null) {
                cookieId = existing;
            } else {
                cookieId = UUID.randomUUID().toString();
                writeCookie(res, cookieId);
                if (log.isDebugEnabled()) {
                    log.debug("Issued cart cookie {} for {} {}",
                            redact(cookieId), req.getMethod(), req.getRequestURI());
                }
            }

            // Expose to downstream handlers (controllers, AuthHandlers, etc.)
            req.setAttribute(REQUEST_ATTR, cookieId);

            chain.doFilter(req, res);
        }

        private boolean shouldSkip(HttpServletRequest req) {
            String path = req.getRequestURI();
            // Tomcat may not include context path here; use a sensible default
            return path.startsWith("/api/")
                    || path.startsWith("/webhooks/")
                    || path.startsWith("/actuator/")
                    || path.startsWith("/css/")
                    || path.startsWith("/scripts/")
                    || path.startsWith("/images/")
                    || path.startsWith("/favicon.ico");
        }

        private String readCookie(HttpServletRequest req) {
            Cookie[] cookies = req.getCookies();
            if (cookies == null) return null;
            for (Cookie c : cookies) {
                if (COOKIE_NAME.equals(c.getName())) {
                    String v = c.getValue();
                    return (v == null || v.isBlank()) ? null : v;
                }
            }
            return null;
        }

        private void writeCookie(HttpServletResponse res, String value) {
            // Use the Cookie API for max-age/HttpOnly/path; add SameSite
            // separately via Set-Cookie header since Cookie API in older
            // servlet versions doesn't support it directly.
            boolean secure = props.cookies() != null && props.cookies().secure();

            // Build Set-Cookie header manually so we can set SameSite=Lax.
            StringBuilder sb = new StringBuilder();
            sb.append(COOKIE_NAME).append('=').append(value);
            sb.append("; Max-Age=").append(MAX_AGE_SECONDS);
            sb.append("; Path=/");
            sb.append("; HttpOnly");
            sb.append("; SameSite=Lax");
            if (secure) sb.append("; Secure");
            res.addHeader("Set-Cookie", sb.toString());
        }

        private static String redact(String id) {
            if (id == null || id.length() < 8) return "***";
            return id.substring(0, 4) + "..." + id.substring(id.length() - 4);
        }
    }
}