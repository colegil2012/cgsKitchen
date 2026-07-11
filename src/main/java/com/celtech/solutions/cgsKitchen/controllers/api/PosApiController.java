package com.celtech.solutions.cgsKitchen.controllers.api;

import com.celtech.solutions.cgsKitchen.config.properties.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.event.EventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.user.UserService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.terminal.ConnectionToken;
import com.stripe.model.terminal.Reader;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.terminal.ConnectionTokenCreateParams;
import com.stripe.param.terminal.ReaderCancelActionParams;
import com.stripe.param.terminal.ReaderProcessPaymentIntentParams;
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
    //  POS customer lookup
    //  Returns 200 {userId, displayName} when the email matches a registered user;
    //  404 when there's no match. Intentionally minimal
    // ------------------------------------------------------------------

    @GetMapping("/pos/customers/lookup")
    public ResponseEntity<?> lookupCustomer(@RequestParam String email) {
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(400)
                    .body(new ErrorResponse("bad_request", "email is required"));
        }
        return userService.findByEmail(email.trim())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(
                        new CustomerMatch(u.getId(), u.getEmail(), u.getDisplayName())))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(new ErrorResponse("not_found",
                                "No registered customer with that email.")));
    }

    public record CustomerMatch(String userId, String email, String displayName) {}


    // ------------------------------------------------------------------
    // POS — create an order from POS system
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
        long tax = Math.round(subtotal * props.storefront().taxRate());
        long total = subtotal + tax;

        // If a registered user is attached, trust the account's email over
        // whatever the terminal echoed back (avoids typos / stale values).
        String customerEmail = req.customerEmail();
        String customerName = req.customerName();
        if (req.userId() != null && !req.userId().isBlank()) {
            var u = userService.findById(req.userId()).orElse(null);
            if (u != null) {
                customerEmail = u.getEmail();
                if (customerName == null || customerName.isBlank()) {
                    customerName = u.getDisplayName();
                }
            }
        }

        var order = Order.builder()
                .source(Order.Source.POS)
                .status(Order.Status.PENDING_PAYMENT)
                .paymentMethod(Order.PaymentMethod.UNPAID)
                .fulfillment(Order.Fulfillment.PICKUP)
                .eventId(req.eventId())
                .userId(req.userId())
                .customerName(customerName)
                .customerEmail(customerEmail)
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

    // ============================================================================
    //  Stripe Terminal — server-driven collection on a physical reader (S710)
    // ============================================================================

    /**
     * Create a card-present PaymentIntent for an existing order and push it to the
     * configured reader so the customer can tap/insert. This is the server-driven
     * equivalent of the SDK's collectPaymentMethod + confirm — the reader handles
     * the card interaction, then Stripe fires payment_intent.succeeded to the
     * webhook, which marks the order PAID/CARD.
     *
     * <p>The order must already exist (created via POST /api/pos/orders) and be in
     * PENDING_PAYMENT. We stamp order_id into the intent metadata so the webhook
     * can find the order — exactly the same contract the web checkout uses, which
     * is why no webhook change is needed.
     *
     * <p>Returns the reader's action status so the POS can show "Present card…".
     * The terminal mock path (Stripe not configured) returns a mock so local dev
     * and tests don't need a real reader.
     */
    @PostMapping("/terminal/collect")
    public ResponseEntity<?> collectOnReader(@Valid @RequestBody CollectRequest req)
            throws StripeException {

        // The order must exist and be awaiting payment.
        Order order = orderService.findById(req.orderId()).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(
                    new ErrorResponse("not_found", "Order " + req.orderId() + " not found"));
        }
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            // Idempotency / safety: don't re-collect on an order that's already
            // paid or otherwise past the pay step.
            return ResponseEntity.status(409).body(
                    new ErrorResponse("not_collectable",
                            "Order is " + order.getStatus() + "; only PENDING_PAYMENT can collect."));
        }

        if (!props.stripe().isConfigured()) {
            // Mock path for local/dev without Stripe keys or a real reader.
            return ResponseEntity.ok(new CollectResponse(
                    order.getId(), "mock_pi", "processing", "mock"));
        }

        String readerId = props.stripe().terminalReaderId();
        if (readerId == null || readerId.isBlank()) {
            return ResponseEntity.status(500).body(
                    new ErrorResponse("no_reader",
                            "No STRIPE_TERMINAL_READER_ID configured on the server."));
        }

        // 1) Create the card_present PaymentIntent (mirrors /terminal/payment-intent,
        //    but amount is taken from the order total — the server is the price
        //    authority, never the client).
        var intentParams = PaymentIntentCreateParams.builder()
                .setAmount(order.getTotalCents())
                .setCurrency("usd")
                .addPaymentMethodType("card_present")
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                .putMetadata("client_id", props.clientId())
                .putMetadata("source", "pos")
                .putMetadata("order_id", order.getId())   // <-- webhook contract
                .build();
        PaymentIntent intent = PaymentIntent.create(intentParams, requestOptions());

        // Persist the PI id on the order now, so a webhook that somehow arrives
        // before we return still finds the order by PI id as a fallback.
        order.setStripePaymentIntentId(intent.getId());
        orderService.save(order);

        // 2) Push the intent to the physical reader. The S710 lights up and
        //    prompts the customer. Authorization happens on the reader.
        try {
            var processParams = ReaderProcessPaymentIntentParams.builder()
                    .setPaymentIntent(intent.getId())
                    .build();
            Reader reader = Reader.retrieve(readerId, requestOptions());
            reader = reader.processPaymentIntent(processParams, requestOptions());

            return ResponseEntity.ok(new CollectResponse(
                    order.getId(),
                    intent.getId(),
                    reader.getAction() == null ? "processing" : reader.getAction().getStatus(),
                    readerId));

        } catch (StripeException e) {
            // Reader-specific failures (busy, offline, timeout) surface here.
            // The order stays PENDING_PAYMENT, so the cashier can retry or fall
            // back to cash. We DON'T mark anything failed — the order is simply
            // not yet paid.
            log.warn("Reader {} failed to process intent {} for order {}: {}",
                    readerId, intent.getId(), order.getId(), e.getMessage());
            return ResponseEntity.status(502).body(
                    new ErrorResponse("reader_error",
                            "Reader could not start payment: " + e.getMessage()));
        }
    }

    /**
     * Cancel the in-progress action on the reader — the cashier hit "Cancel"
     * before the customer presented a card. Clears the prompt on the S710 so it's
     * ready for the next sale. Safe to call even if the reader has no current
     * action (Stripe returns the reader as-is).
     */
    @PostMapping("/terminal/cancel")
    public ResponseEntity<?> cancelReaderAction() throws StripeException {
        if (!props.stripe().isConfigured()) {
            return ResponseEntity.ok(Map.of("status", "mock_cancelled"));
        }
        String readerId = props.stripe().terminalReaderId();
        if (readerId == null || readerId.isBlank()) {
            return ResponseEntity.status(500).body(
                    new ErrorResponse("no_reader", "No reader configured."));
        }
        try {
            Reader reader = Reader.retrieve(readerId, requestOptions());
            reader = reader.cancelAction(
                    ReaderCancelActionParams.builder().build(), requestOptions());
            return ResponseEntity.ok(Map.of(
                    "status",
                    reader.getAction() == null ? "idle" : reader.getAction().getStatus()));
        } catch (StripeException e) {
            // Most commonly "no active action" — treat as already-idle, not an error.
            log.debug("cancelAction on reader {}: {}", readerId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "idle"));
        }
    }

    private RequestOptions requestOptions() {
        return RequestOptions.builder().build();
    }

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


    public record CollectRequest(
            @jakarta.validation.constraints.NotEmpty String orderId
    ) {}

    public record CollectResponse(
            String orderId,
            String paymentIntentId,
            String readerStatus,   // "processing" | "in_progress" | etc.
            String readerId
    ) {}


    public record ErrorResponse(String code, String message) {}
}