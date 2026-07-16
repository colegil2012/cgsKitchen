package com.celtech.solutions.cgsKitchen.controllers.api;

import com.celtech.solutions.cgsKitchen.config.properties.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
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
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/terminal")
@RequiredArgsConstructor
public class PosStripeController {

    private final AppProperties props;
    private final OrderService orderService;

    // ------------------------------------------------------------------
    // Stripe Terminal — connection token + payment intent for in-person
    // ------------------------------------------------------------------

    @PostMapping("/connection-token")
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

    @PostMapping("/payment-intent")
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
    @PostMapping("/collect")
    public ResponseEntity<?> collectOnReader(@Valid @RequestBody CollectRequest req)
            throws StripeException {

        // The order must exist and be awaiting payment.
        Order order = orderService.findById(req.orderId()).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(
                    new PosApiController.ErrorResponse("not_found", "Order " + req.orderId() + " not found"));
        }
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            // Idempotency / safety: don't re-collect on an order that's already
            // paid or otherwise past the pay step.
            return ResponseEntity.status(409).body(
                    new PosApiController.ErrorResponse("not_collectable",
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
                    new PosApiController.ErrorResponse("no_reader",
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
                    new PosApiController.ErrorResponse("reader_error",
                            "Reader could not start payment: " + e.getMessage()));
        }
    }

    /**
     * Cancel the in-progress action on the reader — the cashier hit "Cancel"
     * before the customer presented a card. Clears the prompt on the S710 so it's
     * ready for the next sale. Safe to call even if the reader has no current
     * action (Stripe returns the reader as-is).
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancelReaderAction() throws StripeException {
        if (!props.stripe().isConfigured()) {
            return ResponseEntity.ok(Map.of("status", "mock_cancelled"));
        }
        String readerId = props.stripe().terminalReaderId();
        if (readerId == null || readerId.isBlank()) {
            return ResponseEntity.status(500).body(
                    new PosApiController.ErrorResponse("no_reader", "No reader configured."));
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
}
