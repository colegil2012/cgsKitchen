package com.celtech.solutions.cgsKitchen.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * Sets HTTP response headers that harden the storefront against common
 * web attacks. Registered with low precedence (just above the default
 * filter ordering for security headers) so application logic can still
 * override individual values if it needs to.
 *
 * <h2>Headers set</h2>
 * <dl>
 *   <dt>Content-Security-Policy</dt>
 *   <dd>The browser-enforced allow-list of what the page may load. Our
 *   policy:
 *   <ul>
 *     <li>{@code default-src 'self'} — same-origin baseline</li>
 *     <li>{@code script-src 'self' https://js.stripe.com} — Stripe.js is
 *         the only third-party script we serve</li>
 *     <li>{@code connect-src 'self' https://api.stripe.com} — XHRs/fetch
 *         to our origin and Stripe's API</li>
 *     <li>{@code frame-src https://js.stripe.com https://hooks.stripe.com}
 *         — Stripe Elements + 3DS challenge iframes</li>
 *     <li>{@code img-src 'self' data: https:} — covers any image CDN we
 *         add later without revisiting CSP</li>
 *     <li>{@code style-src 'self' 'unsafe-inline'} — Stripe injects inline
 *         styles into its iframes; this is unavoidable without a stricter
 *         nonce-based scheme, which we'd add only if we move to one</li>
 *     <li>{@code object-src 'none'} — disallow {@code <object>}/Flash entirely</li>
 *     <li>{@code base-uri 'self'} — defends against base-tag injection</li>
 *     <li>{@code form-action 'self'} — forms can only POST back to us</li>
 *     <li>{@code frame-ancestors 'none'} — we are never embedded in
 *         another site's frame (clickjacking defense; equivalent to the
 *         older {@code X-Frame-Options: DENY})</li>
 *   </ul></dd>
 *
 *   <dt>X-Content-Type-Options: nosniff</dt>
 *   <dd>Tells browsers not to guess MIME types — prevents drive-by
 *   {@code script}-execution from a mis-served file.</dd>
 *
 *   <dt>Referrer-Policy: strict-origin-when-cross-origin</dt>
 *   <dd>Outgoing referrers get scoped to the origin only when leaving
 *   the site; in-site navigations keep the full referrer for analytics.</dd>
 *
 *   <dt>Permissions-Policy</dt>
 *   <dd>Disable browser features we don't use (camera, mic, geolocation,
 *   payment-handler API). One less attack surface.</dd>
 * </dl>
 *
 * <p>Spring Security ships defaults for some of these (e.g. it sets
 * {@code X-Frame-Options: DENY}) on the storefront chain; this filter
 * tightens them and adds CSP. Setting the same header twice would be
 * harmless but messy — we explicitly skip duplicates by only writing
 * if the header isn't already present.
 */
@Configuration
public class SecurityHeadersFilter {

    private static final String CSP =
            "default-src 'self'; " +
                    "script-src 'self' https://js.stripe.com https://challenges.cloudflare.com; " +
                    "connect-src 'self' https://api.stripe.com https://challenges.cloudflare.com; " +
                    "frame-src https://js.stripe.com https://hooks.stripe.com https://challenges.cloudflare.com; " +
                    "img-src 'self' data: https:; " +
                    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                    "font-src 'self' https://fonts.gstatic.com; " +
                    "object-src 'none'; " +
                    "base-uri 'self'; " +
                    "form-action 'self'; " +
                    "frame-ancestors 'none'";

    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=()";

    @Bean
    public FilterRegistrationBean<Filter> securityHeadersFilterRegistration() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new Impl());
        reg.addUrlPatterns("/*");
        // Run after Spring Security so any headers it set are visible to
        // our addHeaderIfAbsent guard.
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        reg.setName("securityHeadersFilter");
        return reg;
    }

    static class Impl implements Filter {

        @Override
        public void doFilter(ServletRequest sreq, ServletResponse sres, FilterChain chain)
                throws IOException, ServletException {

            HttpServletResponse res = (HttpServletResponse) sres;
            HttpServletRequest req = (HttpServletRequest) sreq;

            // Skip for the webhook + actuator paths — they're server-to-server
            // and don't render HTML, so CSP is irrelevant noise on those
            // responses.
            String path = req.getRequestURI();
            boolean isHtmlSurface = !(path.startsWith("/webhooks/")
                    || path.startsWith("/actuator/")
                    || path.startsWith("/api/"));

            if (isHtmlSurface) {
                addHeaderIfAbsent(res, "Content-Security-Policy", CSP);
                addHeaderIfAbsent(res, "Permissions-Policy", PERMISSIONS_POLICY);
            }
            // These apply universally — they're cheap and never wrong.
            addHeaderIfAbsent(res, "X-Content-Type-Options", "nosniff");
            addHeaderIfAbsent(res, "Referrer-Policy", "strict-origin-when-cross-origin");

            chain.doFilter(req, res);
        }

        private static void addHeaderIfAbsent(HttpServletResponse res, String name, String value) {
            if (res.getHeader(name) == null) {
                res.setHeader(name, value);
            }
        }
    }
}