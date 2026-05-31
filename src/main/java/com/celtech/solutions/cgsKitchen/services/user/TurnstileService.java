package com.celtech.solutions.cgsKitchen.services.user;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Verifies a Cloudflare Turnstile token against Cloudflare's siteverify
 * endpoint. Open if captcha isn't configured (dev convenience).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnstileService {

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * @param token   value of the hidden {@code cf-turnstile-response} input
     * @param remoteIp optional client IP for risk scoring
     * @return true if the token is valid (or if captcha isn't configured)
     */
    public boolean verify(String token, String remoteIp) {
        if (!props.captcha().isConfigured()) {
            log.debug("Turnstile not configured — accepting all submissions");
            return true;
        }
        if (token == null || token.isBlank()) return false;

        String body = "secret=" + URLEncoder.encode(props.captcha().secretKey(), StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                + (remoteIp == null ? "" : "&remoteip=" + URLEncoder.encode(remoteIp, StandardCharsets.UTF_8));

        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(VERIFY_URL))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JsonNode node = mapper.readTree(resp.body());
            boolean success = node.path("success").asBoolean(false);
            if (!success) {
                log.warn("Turnstile verification failed: {}", node);
            }
            return success;
        } catch (Exception ex) {
            log.error("Turnstile verify call failed", ex);
            return false;
        }
    }
}