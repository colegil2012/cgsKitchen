package com.celtech.solutions.cgsKitchen.delivery.uber;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OAuth2 client_credentials token manager for Uber Direct.
 *
 * <p>Tokens are valid for 30 days. We cache the current token in memory
 * and refresh it when it's within {@link #REFRESH_BEFORE_EXPIRY} of expiry,
 * or on first use after a token-related failure. Cache is per-application
 * instance — fine for single-node dev, would need Redis or similar for
 * multi-node prod.
 *
 * <p>Auth endpoint and scope are stable across sandbox and production —
 * {@code https://auth.uber.com/oauth/v2/token} with scope
 * {@code eats.deliveries}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UberDirectAuthClient {

    private static final String TOKEN_URL = "https://auth.uber.com/oauth/v2/token";
    private static final String SCOPE = "eats.deliveries";

    /** Refresh ahead of expiry to avoid using a token that expires mid-request. */
    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofHours(24);

    private final AppProperties props;
    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt;

    /**
     * Return a valid bearer token, refreshing if necessary. Thread-safe;
     * concurrent callers during refresh will all see the new token.
     */
    public String getAccessToken() {
        if (isFresh()) {
            return cachedToken;
        }
        refreshLock.lock();
        try {
            // Re-check inside the lock — another thread may have refreshed.
            if (isFresh()) {
                return cachedToken;
            }
            refreshToken();
            return cachedToken;
        } finally {
            refreshLock.unlock();
        }
    }

    /** Force a refresh on next call — useful after a 401 from a downstream call. */
    public void invalidate() {
        cachedToken = null;
        tokenExpiresAt = null;
        log.info("Uber Direct token invalidated; will refresh on next use");
    }

    private boolean isFresh() {
        return cachedToken != null
                && tokenExpiresAt != null
                && Instant.now().plus(REFRESH_BEFORE_EXPIRY).isBefore(tokenExpiresAt);
    }

    private void refreshToken() {
        AppProperties.Uber uber = props.delivery().uber();
        if (uber == null
                || uber.clientId() == null || uber.clientId().isBlank()
                || uber.clientSecret() == null || uber.clientSecret().isBlank()) {
            throw new IllegalStateException("Uber Direct credentials not configured");
        }

        String body = "client_id=" + url(uber.clientId())
                + "&client_secret=" + url(uber.clientSecret())
                + "&grant_type=client_credentials"
                + "&scope=" + url(SCOPE);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.error("Uber token endpoint returned {}: {}", res.statusCode(), res.body());
                throw new RuntimeException("Uber Direct authentication failed: HTTP " + res.statusCode());
            }
            JsonNode node = json.readTree(res.body());
            String token = node.path("access_token").asText(null);
            long expiresInSec = node.path("expires_in").asLong(2_592_000L);
            if (token == null || token.isBlank()) {
                throw new RuntimeException("Uber token endpoint returned no access_token");
            }
            cachedToken = token;
            tokenExpiresAt = Instant.now().plusSeconds(expiresInSec);
            log.info("Uber Direct token refreshed (expires at {})", tokenExpiresAt);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Uber Direct authentication call failed", e);
        }
    }

    private static String url(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
    }
}