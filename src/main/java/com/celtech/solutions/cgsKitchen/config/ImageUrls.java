package com.celtech.solutions.cgsKitchen.config;

import com.celtech.solutions.cgsKitchen.util.ImageUrlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Spring-wired wrapper around {@link ImageUrlUtil}, exposing the
 * configured base URL to Thymeleaf templates.
 *
 * <p>In templates, call as:
 * <pre>
 *   &lt;img th:src="${@imageUrls.resolve(brand.images().logoPath())}" /&gt;
 *   &lt;img th:src="${@imageUrls.resolve('/images/hero.jpg')}" /&gt;
 * </pre>
 *
 * <p>The bean name {@code imageUrls} (camelCase from the class name) is
 * what Thymeleaf's SpEL uses to look it up via the {@code @beanName}
 * syntax. Don't rename without updating template references.
 *
 * <p><b>Path convention:</b> store image paths as
 * {@code /images/category/file.ext}. In dev, Spring's static-resource
 * handler serves them from {@code src/main/resources/static/images/}.
 * In prod, the {@code app.storefront.images.base-url} property is set
 * to the DigitalOcean Spaces CDN URL, and the utility rewrites paths
 * to point there (stripping the {@code /images/} prefix to match the
 * bucket's root layout).
 */
@Component("imageUrls")
@RequiredArgsConstructor
public class ImageUrls {

    private final AppProperties props;

    /**
     * Resolve a path to its environment-appropriate URL. Safe to call
     * with null/blank — returns the placeholder image.
     */
    public String resolve(String path) {
        return ImageUrlUtil.resolve(path, baseUrl());
    }

    /**
     * The configured CDN base URL. Empty/null in dev, populated in prod.
     * Public so templates / Java callers can reference it directly for
     * meta tags (og:image needs an absolute URL).
     */
    public String baseUrl() {
        var storefront = props.storefront();
        if (storefront == null || storefront.images() == null) return null;
        return storefront.images().baseUrl();
    }

    /**
     * Resolve to an absolute URL where possible. If a CDN base is
     * configured, this is identical to {@link #resolve}. If not (dev
     * mode), the caller is responsible for prefixing with the site's
     * host — typical use is in emails or og:image meta tags where
     * relative URLs don't work.
     *
     * <p>Returns null only if the path is blank AND no CDN is configured
     * AND no fallback applies — never expected in practice (placeholder
     * fallback handles blank path).
     */
    public String resolveAbsolute(String path) {
        String resolved = resolve(path);
        if (resolved == null || resolved.startsWith("http://") || resolved.startsWith("https://")) {
            return resolved;
        }
        // Dev mode: no CDN, can't return absolute. Caller's problem.
        return resolved;
    }
}