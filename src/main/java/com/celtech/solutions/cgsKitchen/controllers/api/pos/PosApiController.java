package com.celtech.solutions.cgsKitchen.controllers.api.pos;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.terminal.ConnectionToken;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.terminal.ConnectionTokenCreateParams;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the POS terminal (and any other authenticated
 * external client). Auth is via the X-API-Key / Authorization: Bearer
 * header configured in {@code app.api-key}.
 *
 * <p>Three groups of endpoints:
 * <ul>
 *   <li>{@code /api/orders} — list, get, status updates
 *   <li>{@code /api/pos/orders} — create an order from POS-entered items
 *   <li>{@code /api/terminal/*} — Stripe Terminal connection-token and
 *       payment-intent issuance
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PosApiController {

    private final OrderService orderService;
    private final UserService userService;
    private final AppProperties props;
    private final EventService eventService;


    // ------------------------------------------------------------------
    // POS — create an order from items being rung up
    // ------------------------------------------------------------------

    @PostMapping("/pos/orders")
    public ResponseEntity<?> createPosOrder(@Valid @RequestBody PosOrderRequest req) {
        // --- Event linkage guard: no orphaned orders. ---
        // We check EXISTENCE, not liveness. An offline cash order flushed
        // after its event ended is legitimate and must succeed; only a
        // missing/garbage eventId is rejected.
        if (req.eventId() == null || req.eventId().isBlank()) {
            return ResponseEntity.status(400).body(
                    new ErrorResponse("missing_event",
                            "Order must include an eventId. No event was active at ring-up."));
        }
        if (eventService.findById(req.eventId()).isEmpty()) {
            return ResponseEntity.status(400).body(
                    new ErrorResponse("unknown_event",
                            "eventId '" + req.eventId() + "' does not reference a known event."));
        }

        long subtotal = req.items().stream()
                .mapToLong(i -> i.unitPriceCents() * i.quantity())
                .sum();
        long tax = Math.round(subtotal * 0.07);
        long total = subtotal + tax;

        var order = Order.builder()
                .source(Order.Source.POS)
                .status(Order.Status.PENDING_PAYMENT)
                .paymentMethod(Order.PaymentMethod.UNPAID)
                .fulfillment(Order.Fulfillment.PICKUP)
                .eventId(req.eventId())
                .userId(req.userId())
                .customerName(req.customerName())
                .customerEmail(req.customerEmail())
                .items(req.items().stream()
                        .map(i -> Order.LineItem.builder()
                                .menuItemId(i.menuItemId())
                                .name(i.name())
                                .quantity(i.quantity())
                                .unitPriceCents(i.unitPriceCents())
                                .modifiers(i.modifiers() == null ? List.of() : i.modifiers())
                                .build())
                        .toList())
                .subtotalCents(subtotal)
                .taxCents(tax)
                .totalCents(total)
                .build();
        var saved = orderService.save(order);
        return ResponseEntity.created(URI.create("/api/orders/" + saved.getId()))
                .body(saved);
    }

    //
    //  GET /api/pos/customers/lookup?email=...
    //  Returns 200 {userId, displayName} when the email matches a registered user;
    //  404 when there's no match. Intentionally minimal — this is an
    //  account-enumeration surface, so it returns only what the POS needs to
    //  attach the order (id + a name to show), nothing more. Safe here because the
    //  endpoint is behind the API-key chain (only the terminal can call it).

    @GetMapping("/pos/customers/lookup")
    public ResponseEntity<?> lookupCustomer(@RequestParam String email) {
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("bad_request", "email is required"));
        }
        return userService.findByEmail(email.trim())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(
                        new CustomerMatch(u.getId(), u.getDisplayName())))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ErrorResponse("not_found",
                                "No registered customer with that email.")));
    }

    public record CustomerMatch(String userId, String displayName) {}



    // ------------------------------------------------------------------
    // Stripe Terminal — connection token + payment intent for in-person
    // ------------------------------------------------------------------

    @PostMapping("/terminal/connection-token")
    public Map<String, String> connectionToken() throws StripeException {
        if (!props.stripe().isConfigured()) {
            return Map.of("secret", "mock_token");
        }
        var params = ConnectionTokenCreateParams.builder()
                .setLocation(props.stripe().terminalLocationId())
                .build();
        var token = ConnectionToken.create(params, requestOptions());
        return Map.of("secret", token.getSecret());
    }

    @PostMapping("/terminal/payment-intent")
    public Map<String, String> paymentIntent(@Valid @RequestBody IntentRequest req)
            throws StripeException {
        if (!props.stripe().isConfigured()) {
            return Map.of("clientSecret", "mock_pi_secret",
                    "orderId", req.orderId() == null ? "mock_order" : req.orderId());
        }

        var paramsBuilder = PaymentIntentCreateParams.builder()
                .setAmount(req.amount())
                .setCurrency("usd")
                .addPaymentMethodType("card_present")
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                .putMetadata("client_id", props.clientId())
                .putMetadata("source", "pos");

        if (req.orderId() != null) {
            paramsBuilder.putMetadata("order_id", req.orderId());
        }

        var intent = PaymentIntent.create(paramsBuilder.build(), requestOptions());

        if (req.orderId() != null) {
            orderService.findById(req.orderId()).ifPresent(o -> {
                o.setStripePaymentIntentId(intent.getId());
                orderService.save(o);
            });
        }

        return Map.of(
                "clientSecret", intent.getClientSecret(),
                "orderId", req.orderId() == null ? "" : req.orderId()
        );
    }

    private RequestOptions requestOptions() {
        return RequestOptions.builder().build();
    }

    // ---- DTOs ----

    //  The POS sends userId (from a successful lookup) plus the display name/email
    //  so the admin views show the customer without a join. All optional — a plain
    //  walk-up cash sale sends none of them and shows as "Walk-in (POS)".

    public record PosOrderRequest(
            @NotEmpty List<PosLineItem> items,
            String eventId,
            String userId,          // null for walk-in; set when attached to a user
            String customerName,    // denormalized for display
            String customerEmail
    ) {}

    public record PosLineItem(
            String menuItemId,
            String name,
            @Positive int quantity,
            @Positive long unitPriceCents,
            List<String> modifiers   // "Group: Choice" labels; may be null/empty
    ) {}

    public record IntentRequest(
            @Positive long amount,
            String orderId
    ) {}

    public record ErrorResponse(String code, String message) {}
}