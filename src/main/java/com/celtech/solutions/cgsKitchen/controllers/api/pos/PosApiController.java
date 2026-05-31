package com.celtech.solutions.cgsKitchen.controllers.api.pos;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
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
    private final AppProperties props;


    // ------------------------------------------------------------------
    // POS — create an order from items being rung up
    // ------------------------------------------------------------------

    @PostMapping("/pos/orders")
    public ResponseEntity<Order> createPosOrder(@Valid @RequestBody PosOrderRequest req) {
        long subtotal = req.items().stream()
                .mapToLong(i -> i.unitPriceCents() * i.quantity())
                .sum();
        long tax = Math.round(subtotal * 0.07);
        long total = subtotal + tax;

        var order = Order.builder()
                .source(Order.Source.POS)
                .status(Order.Status.PENDING_PAYMENT)
                .fulfillment(Order.Fulfillment.PICKUP)
                .items(req.items().stream()
                        .map(i -> Order.LineItem.builder()
                                .menuItemId(i.menuItemId())
                                .name(i.name())
                                .quantity(i.quantity())
                                .unitPriceCents(i.unitPriceCents())
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

    private RequestOptions requestOptions() {
        return RequestOptions.builder().build();
    }

    // ---- DTOs ----

    public record PosOrderRequest(@NotEmpty List<PosLineItem> items) {}

    public record PosLineItem(
            String menuItemId,
            String name,
            @Positive int quantity,
            @Positive long unitPriceCents
    ) {}

    public record IntentRequest(
            @Positive long amount,
            String orderId
    ) {}
}
