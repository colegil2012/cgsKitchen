package com.celtech.solutions.cgsKitchen.delivery.uber;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin REST wrapper around Uber Direct's customer-scoped endpoints.
 *
 * <p>Base URL is hard-coded to {@code https://api.uber.com/v1/customers/{customer_id}}.
 * That same host serves both sandbox and production — Uber distinguishes
 * environments via credentials, not URL. The {@code customer_id} comes from
 * {@code app.delivery.uber.customer-id}.
 *
 * <p>Addresses on quote/delivery payloads are quirky: they're JSON values
 * but Uber expects them encoded as <em>strings</em>. We build the inner
 * JSON, serialize it, then send it as a string field in the outer JSON.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UberDirectClient {

    private static final String BASE = "https://api.uber.com/v1/customers/";

    private final AppProperties props;
    private final UberDirectAuthClient auth;
    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ================================================================
    //  Quotes
    // ================================================================

    /**
     * POST /delivery_quotes — get a fee + ETA estimate. Quotes are valid
     * for 15 minutes; the returned id must be passed to {@link #createDelivery}.
     *
     * <p>Addresses are <strong>stringified JSON</strong> per Uber's spec.
     */
    public QuoteResponse createQuote(UberAddress pickup, UberAddress dropoff) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup_address", json.writeValueAsString(pickup.toMap()));
        body.put("dropoff_address", json.writeValueAsString(dropoff.toMap()));

        JsonNode node = post(customerPath() + "/delivery_quotes", body);

        return new QuoteResponse(
                node.path("id").asText(),
                node.path("fee").asLong(),
                node.path("currency").asText("usd"),
                node.path("duration").asInt(),
                node.path("pickup_duration").asInt(),
                node.path("dropoff_eta").asText(null),
                node.path("expires").asText(null)
        );
    }

    // ================================================================
    //  Deliveries
    // ================================================================

    /**
     * POST /deliveries — dispatch a courier. The {@code externalOrderId} is
     * surfaced to the courier; we use our internal Order id.
     */
    public DeliveryResponse createDelivery(CreateDeliveryRequest req) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quote_id", req.quoteId());
        body.put("pickup_address", json.writeValueAsString(req.pickup().toMap()));
        body.put("pickup_name", req.pickupName());
        body.put("pickup_phone_number", req.pickupPhone());
        body.put("dropoff_address", json.writeValueAsString(req.dropoff().toMap()));
        body.put("dropoff_name", req.dropoffName());
        body.put("dropoff_phone_number", req.dropoffPhone());
        body.put("manifest_items", req.manifestItems());
        body.put("external_id", req.externalOrderId());
        if (req.dropoffNotes() != null && !req.dropoffNotes().isBlank()) {
            body.put("dropoff_notes", req.dropoffNotes());
        }
        if (req.tipCents() > 0) {
            body.put("tip", req.tipCents());
        }

        JsonNode node = post(customerPath() + "/deliveries", body);
        return parseDelivery(node);
    }

    /**
     * GET /deliveries/{id} — current state of a delivery. Used as a polling
     * fallback when webhooks are missed.
     */
    public DeliveryResponse getDelivery(String deliveryId) throws Exception {
        JsonNode node = get(customerPath() + "/deliveries/" + deliveryId);
        return parseDelivery(node);
    }

    /**
     * POST /deliveries/{id}/cancel — cancel before pickup. Returns the
     * updated delivery in its terminal state.
     */
    public DeliveryResponse cancelDelivery(String deliveryId) throws Exception {
        JsonNode node = post(customerPath() + "/deliveries/" + deliveryId + "/cancel",
                Map.of());
        return parseDelivery(node);
    }

    private DeliveryResponse parseDelivery(JsonNode node) {
        String courierName = null;
        String courierPhone = null;
        Double courierLat = null;
        Double courierLng = null;
        if (node.hasNonNull("courier")) {
            courierName = node.path("courier").path("name").asText(null);
            courierPhone = node.path("courier").path("phone_number").asText(null);
            if (node.path("courier").hasNonNull("location")) {
                courierLat = node.path("courier").path("location").path("lat").asDouble();
                courierLng = node.path("courier").path("location").path("lng").asDouble();
            }
        }
        return new DeliveryResponse(
                node.path("id").asText(),
                node.path("status").asText(),
                node.path("tracking_url").asText(null),
                node.path("fee").asLong(),
                node.path("dropoff_eta").asText(null),
                courierName,
                courierPhone,
                courierLat,
                courierLng
        );
    }

    // ================================================================
    //  HTTP plumbing
    // ================================================================

    private String customerPath() {
        String customerId = props.delivery().uber().customerId();
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalStateException("Uber Direct customer_id not configured");
        }
        return BASE + customerId;
    }

    private JsonNode post(String url, Map<String, Object> body) throws Exception {
        String payload = json.writeValueAsString(body);
        return execute(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(), url, "POST", payload);
    }

    private JsonNode get(String url) throws Exception {
        return execute(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + auth.getAccessToken())
                .GET()
                .build(), url, "GET", null);
    }

    private JsonNode execute(HttpRequest req, String url, String method, String payload)
            throws Exception {
        return RetryHelper.withRetries(
                method + " " + url,
                () -> doExecute(req, url, method, payload),
                RetryHelper::isUberRetryable);
    }

    private JsonNode doExecute(HttpRequest req, String url, String method, String payload)
            throws Exception {
        log.debug("Uber {} {} body={}", method, url, payload);
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        log.debug("Uber {} {} → {}", method, url, res.statusCode());

        if (res.statusCode() == 401 || res.statusCode() == 403) {
            auth.invalidate();
            throw new UberDirectException(
                    res.statusCode(),
                    "Uber Direct authentication rejected",
                    res.body());
        }
        if (res.statusCode() / 100 != 2) {
            throw new UberDirectException(
                    res.statusCode(),
                    "Uber Direct call failed: " + url,
                    res.body());
        }
        return json.readTree(res.body());
    }

    // ================================================================
    //  Data shapes
    // ================================================================

    public record UberAddress(
            List<String> streetAddress,
            String city,
            String state,
            String zipCode,
            String country
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("street_address", streetAddress);
            m.put("city", city);
            m.put("state", state);
            m.put("zip_code", zipCode);
            m.put("country", country == null ? "US" : country);
            return m;
        }

        /**
         * Build from a single-line US address. Accepts both common forms:
         *   "123 Main St, City, ST 40165"   (two commas — preferred/canonical)
         *   "123 Main St, City ST 40165"    (one comma — also acceptable)
         *
         * <p>The trailing "STATE ZIP" pattern is matched with a regex so we
         * can identify it regardless of whether a comma separates it from
         * the city.
         */
        public static UberAddress parseSingleLine(String line) {
            if (line == null || line.isBlank()) {
                throw new IllegalArgumentException("Address line is empty");
            }

            // Trailing "ST 40165" / "ST, 40165" / "ST 40165-1234" — capture state and zip.
            // Tolerates a comma OR whitespace between state and zip, and an optional
            // leading comma before the state (handles both "City, ST ZIP" and
            // "City ST ZIP" patterns).
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)\\s*,?\\s*([A-Z]{2})\\s*,?\\s+(\\d{5}(?:-\\d{4})?)\\s*$")
                    .matcher(line);
            if (!m.find()) {
                throw new IllegalArgumentException(
                        "Address must end with 'STATE ZIP' (e.g. 'KY 40165'): " + line);
            }
            String state = m.group(1).toUpperCase();
            String zip = m.group(2);
            String beforeStateZip = line.substring(0, m.start()).trim();

            // What's left should be "Street, City" (with at least one comma)
            // OR just "Street City" without a comma — fall back if so.
            String street;
            String city;
            int lastComma = beforeStateZip.lastIndexOf(',');
            if (lastComma > 0) {
                street = beforeStateZip.substring(0, lastComma).trim();
                city = beforeStateZip.substring(lastComma + 1).trim();
            } else {
                // No comma between street and city — split on last whitespace
                // before what looks like a city name (best effort).
                int lastSpace = beforeStateZip.lastIndexOf(' ');
                if (lastSpace > 0) {
                    street = beforeStateZip.substring(0, lastSpace).trim();
                    city = beforeStateZip.substring(lastSpace + 1).trim();
                } else {
                    throw new IllegalArgumentException(
                            "Could not separate street from city in: " + line);
                }
            }
            if (street.isBlank() || city.isBlank()) {
                throw new IllegalArgumentException(
                        "Empty street or city after parsing: " + line);
            }

            return new UberAddress(List.of(street), city, state, zip, "US");
        }
    }

    public record QuoteResponse(
            String id,
            long feeCents,
            String currency,
            int durationMinutes,
            int pickupDurationMinutes,
            String dropoffEta,
            String expires
    ) {}

    public record CreateDeliveryRequest(
            String quoteId,
            UberAddress pickup,
            String pickupName,
            String pickupPhone,
            UberAddress dropoff,
            String dropoffName,
            String dropoffPhone,
            String dropoffNotes,
            List<ManifestItem> manifestItems,
            String externalOrderId,
            long tipCents
    ) {}

    public record ManifestItem(
            String name,
            int quantity,
            int price,         // cents
            Dimensions dimensions,
            int weight          // grams
    ) {
        public static ManifestItem simple(String name, int quantity, int priceCents) {
            return new ManifestItem(name, quantity, priceCents,
                    new Dimensions(15, 15, 15), 500);
        }
    }

    public record Dimensions(int length, int height, int depth) {}

    public record DeliveryResponse(
            String id,
            String status,
            String trackingUrl,
            long feeCents,
            String dropoffEta,
            String courierName,
            String courierPhone,
            Double courierLat,
            Double courierLng
    ) {}

    public static class UberDirectException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;
        public UberDirectException(int statusCode, String msg, String responseBody) {
            super(msg + " (HTTP " + statusCode + "): " + responseBody);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
        public int statusCode() { return statusCode; }
        public String responseBody() { return responseBody; }
    }
}