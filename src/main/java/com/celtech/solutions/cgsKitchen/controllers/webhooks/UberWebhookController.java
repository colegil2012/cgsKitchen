package com.celtech.solutions.cgsKitchen.controllers.webhooks;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.delivery.uber.UberDeliveryStatusMapper;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.DeliveryEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.webhooks.OrderLockService;
import com.celtech.solutions.cgsKitchen.services.webhooks.WebhookEventService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

/**
 * Uber Direct webhook endpoint, with refined order-status semantics.
 *
 * <p><strong>Key behavior change:</strong> the mapper now uses a
 * {@link UberDeliveryStatusMapper.Decision} object so we can distinguish
 * "transition the order" from "flag for kitchen attention" from "log only".
 *
 * <ul>
 *   <li>{@code pending}, {@code pickup} — courier being arranged / en
 *       route to pickup. Food is still in the kitchen; order status
 *       does NOT change. Logged to delivery_events.</li>
 *   <li>{@code pickup_complete} — courier has the food. Order moves to
 *       OUT_FOR_DELIVERY (bypassing the POS transition matrix —
 *       observation, not decision).</li>
 *   <li>{@code delivered} — order COMPLETED.</li>
 *   <li>{@code canceled} mid-flight — DOES NOT auto-cancel the order.
 *       Sets {@code deliveryAttentionRequired=true} and persists the
 *       cancellation reason. Kitchen handles via POS.</li>
 * </ul>
 *
 * <p>Every webhook is also logged to the {@code delivery_events} stream
 * for forensic / customer-tracking purposes.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/uber")
@RequiredArgsConstructor
public class UberWebhookController {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final AppProperties props;
    private final OrderService orderService;
    private final ObjectMapper json = new ObjectMapper();
    private final WebhookEventService webhookEvents;
    private final OrderLockService orderLocks;
    private final OrderEventService orderEvents;
    private final DeliveryEventService deliveryEvents;

    @PostMapping
    public ResponseEntity<String> handle(
            @RequestBody String payload,
            @RequestHeader(value = "x-uber-signature", required = false) String signature,
            @RequestHeader(value = "x-environment", required = false) String environment) {

        if (!verifySignature(payload, signature)) {
            log.warn("Invalid Uber webhook signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        JsonNode event;
        try {
            event = json.readTree(payload);
        } catch (Exception e) {
            log.warn("Could not parse Uber webhook payload", e);
            return ResponseEntity.status(400).body("Invalid JSON");
        }

        String eventId = event.path("id").asString("");
        String kind = event.path("kind").asString("");

        if (!isFresh(event)) {
            log.warn("Stale Uber webhook (id={}, kind={}) — rejecting as potential replay",
                    eventId, kind);
            return ResponseEntity.status(400).body("Stale event");
        }

        WebhookEventService.Outcome receipt = webhookEvents.recordReceipt(
                "uber", eventId, kind, payload);
        if (receipt.isDuplicate()) {
            return ResponseEntity.ok("duplicate");
        }

        String deliveryId = event.path("delivery_id").asString(
                event.path("data").path("id").asString(null));
        String status = event.path("status").asString(
                event.path("data").path("status").asString(null));

        log.info("Uber webhook: kind={} env={} delivery={} status={}",
                kind, environment, deliveryId, status);

        try {
            switch (kind) {
                case "event.delivery_status" -> handleDeliveryStatus(deliveryId, status, event);
                case "event.courier_update" -> handleCourierUpdate(deliveryId, event);
                default -> log.debug("Unhandled Uber webhook kind: {}", kind);
            }
            if (receipt.isNew()) webhookEvents.markProcessed(receipt.event());
        } catch (Exception e) {
            log.error("Uber webhook handler failed for delivery {}", deliveryId, e);
            if (receipt.isNew()) webhookEvents.markFailed(receipt.event(), e.getMessage());
            return ResponseEntity.status(500).body("Handler failed");
        }

        return ResponseEntity.ok("ok");
    }

    // ================================================================
    //  Delivery status events
    // ================================================================

    private void handleDeliveryStatus(String deliveryId, String status, JsonNode event) {
        if (deliveryId == null) return;
        Order found = orderService.findByDeliveryExternalId(deliveryId).orElse(null);
        if (found == null) {
            log.warn("Uber status for unknown delivery {}", deliveryId);
            return;
        }
        final String orderId = found.getId();

        // Extract cancellation reason (if present) and tracking URL up here
        // so we can pass them to the locked block.
        JsonNode cancelNode = event.path("data").path("cancelation_reason");
        String cancelPrimary = cancelNode.path("primary_reason").asString(null);
        String cancelSecondary = cancelNode.path("secondary_reason").asString(null);
        String trackingUrl = event.path("tracking_url").asString(
                event.path("data").path("tracking_url").asString(null));

        // Telemetry stream — always records, regardless of order transition.
        deliveryEvents.record(orderId, deliveryId, "uber", status,
                "delivery_status",
                cancelPrimary != null
                        ? "cancel reason: " + cancelPrimary
                          + (cancelSecondary != null ? " / " + cancelSecondary : "")
                        : null);

        orderLocks.withLock(orderId, () -> {
            Order order = orderService.findById(orderId).orElse(null);
            if (order == null) return;

            UberDeliveryStatusMapper.Decision decision =
                    UberDeliveryStatusMapper.decide(status, order.getStatus());

            boolean changed = false;

            // Status transition — bypasses the POS transition matrix because
            // this is an OBSERVATION of external reality (the courier physically
            // took the food), not a kitchen decision.
            if (decision.changesStatus()) {
                Order.Status prev = order.getStatus();
                order.setStatus(decision.nextStatus());
                orderEvents.record(orderId, prev, decision.nextStatus(),
                        "uber-webhook", null, "uber status=" + status);
                log.info("Order {} : {} → {} (uber status={})",
                        orderId, prev, decision.nextStatus(), status);
                changed = true;
            }

            // Attention flag — cancellation arrived; kitchen must intervene.
            if (decision.markAttentionRequired() && !order.isDeliveryAttentionRequired()) {
                order.setDeliveryAttentionRequired(true);
                log.info("Order {} : delivery attention required (uber status={}, reason={})",
                        orderId, status, cancelPrimary);
                changed = true;
            }

            // Cancellation reason (overwrite each time — most recent is freshest).
            if (cancelPrimary != null && !cancelPrimary.equals(order.getCancellationReason())) {
                order.setCancellationReason(cancelPrimary);
                changed = true;
            }
            if (cancelSecondary != null && !cancelSecondary.equals(order.getCancellationDetail())) {
                order.setCancellationDetail(cancelSecondary);
                changed = true;
            }

            // Tracking URL back-fill.
            if (trackingUrl != null && order.getDeliveryTrackingUrl() == null) {
                order.setDeliveryTrackingUrl(trackingUrl);
                changed = true;
            }

            if (changed) orderService.save(order);
        });
    }

    // ================================================================
    //  Courier position updates
    // ================================================================

    private void handleCourierUpdate(String deliveryId, JsonNode event) {
        if (deliveryId == null) return;
        Order order = orderService.findByDeliveryExternalId(deliveryId).orElse(null);
        if (order == null) {
            log.debug("Courier update for unknown delivery {}", deliveryId);
            return;
        }
        double lat = event.path("location").path("lat").asDouble(0);
        double lng = event.path("location").path("lng").asDouble(0);

        // Telemetry only — does not touch the order.
        deliveryEvents.record(order.getId(), deliveryId, "uber", null,
                "courier_update", null,
                lat == 0 ? null : lat,
                lng == 0 ? null : lng);

        if (log.isDebugEnabled()) {
            log.debug("Courier update for order {} (delivery {}): lat={} lng={}",
                    order.getId(), deliveryId, lat, lng);
        }
    }

    // ================================================================
    //  Freshness check
    // ================================================================

    private boolean isFresh(JsonNode event) {
        String created = event.path("created").asString(null);
        if (created == null) return true;
        try {
            Instant when = Instant.parse(created);
            return Duration.between(when, Instant.now()).compareTo(MAX_AGE) <= 0;
        } catch (DateTimeParseException e) {
            log.warn("Unparseable Uber webhook timestamp: {}", created);
            return true;
        }
    }

    // ================================================================
    //  Signature verification
    // ================================================================

    private boolean verifySignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) return false;
        String secret = props.delivery().uber().webhookSigningKey();
        if (secret == null || secret.isBlank()) {
            log.error("Uber webhook signing key not configured — rejecting all webhooks");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(computed);
            return constantTimeEquals(hex, signature.toLowerCase());
        } catch (Exception e) {
            log.error("Signature verification threw", e);
            return false;
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}