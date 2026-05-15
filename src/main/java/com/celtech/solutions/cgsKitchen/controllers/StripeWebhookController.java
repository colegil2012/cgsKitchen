package com.celtech.solutions.cgsKitchen.controllers;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.delivery.DeliveryProvider;
import com.celtech.solutions.cgsKitchen.models.Order;
import com.celtech.solutions.cgsKitchen.services.OrderService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stripe webhook endpoint.
 *
 * <p>Signature verification rejects forged calls. Successful payments
 * are the authoritative signal that an order is paid — never trust the
 * customer's redirect alone.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final AppProperties props;
    private final OrderService orderService;
    private final DeliveryProvider delivery;

    @PostMapping
    public ResponseEntity<String> handle(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature
    ) {
        Event event;
        try {
            event = Webhook.constructEvent(
                    payload, signature, props.stripe().webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        log.info("Stripe webhook: {} ({})", event.getType(), event.getId());

        try {
            switch (event.getType()) {
                case "checkout.session.completed" -> handleCheckoutCompleted(event);
                case "payment_intent.succeeded" -> handleIntentSucceeded(event);
                case "charge.refunded" -> handleRefund(event);
                default -> log.debug("Unhandled event type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("Webhook handler failed", e);
            return ResponseEntity.status(500).body("Handler failed");
        }

        return ResponseEntity.ok("ok");
    }

    private void handleCheckoutCompleted(Event event) {
        var session = (Session) event.getDataObjectDeserializer().getObject().orElseThrow();
        var order = orderService.findByCheckoutSessionId(session.getId()).orElse(null);
        if (order == null) {
            log.warn("Checkout completed for unknown session: {}", session.getId());
            return;
        }

        order.setStatus(Order.Status.PAID);
        orderService.save(order);
        log.info("Order {} → PAID", order.getId());

        // Auto-dispatch delivery if applicable
        if (order.getFulfillment() == Order.Fulfillment.DELIVERY
                && order.getDeliveryAddress() != null) {
            try {
                var dispatch = delivery.dispatch(new DeliveryProvider.DispatchRequest(
                        order.getId(),
                        props.delivery().pickupAddress(),
                        order.getDeliveryAddress(),
                        order.getCustomerName() == null ? "Customer" : order.getCustomerName(),
                        order.getCustomerPhone() == null ? "" : order.getCustomerPhone(),
                        order.getTotalCents(),
                        order.getTipCents()
                ));
                order.setDeliveryProvider(delivery.name());
                order.setDeliveryExternalId(dispatch.externalDeliveryId());
                order.setDeliveryTrackingUrl(dispatch.trackingUrl());
                order.setStatus(Order.Status.OUT_FOR_DELIVERY);
                orderService.save(order);
                log.info("Order {} dispatched via {} ({})",
                         order.getId(), delivery.name(), dispatch.externalDeliveryId());
            } catch (Exception e) {
                log.error("Delivery dispatch failed for order {}", order.getId(), e);
            }
        }
    }

    private void handleIntentSucceeded(Event event) {
        var obj = event.getDataObjectDeserializer().getObject().orElse(null);
        if (!(obj instanceof PaymentIntent intent)) return;

        orderService.findByPaymentIntentId(intent.getId()).ifPresent(order -> {
            order.setStatus(Order.Status.PAID);
            orderService.save(order);
            log.info("Order {} → PAID via PaymentIntent {}", order.getId(), intent.getId());
        });
    }

    private void handleRefund(Event event) {
        log.info("Refund event received — TODO: update order status to REFUNDED");
    }
}
