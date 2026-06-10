package com.celtech.solutions.cgsKitchen.controllers.webhooks;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.models.user.PaymentMethod;
import com.celtech.solutions.cgsKitchen.models.user.User;
import com.celtech.solutions.cgsKitchen.repositories.user.PaymentMethodRepository;
import com.celtech.solutions.cgsKitchen.repositories.user.UserRepository;
import com.celtech.solutions.cgsKitchen.services.mail.OrderConfirmationEmail;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.storefront.shop.CartService;
import com.celtech.solutions.cgsKitchen.services.storefront.shop.PaymentMetrics;
import com.celtech.solutions.cgsKitchen.services.webhooks.OrderLockService;
import com.celtech.solutions.cgsKitchen.services.webhooks.WebhookEventService;
import com.celtech.solutions.cgsKitchen.services.webhooks.WebhookHandlerFailedEvent;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;

/**
 * Stripe webhook endpoint.
 *
 * <p><strong>Note: this controller NO LONGER dispatches Uber.</strong>
 * The previous version transitioned the order to OUT_FOR_DELIVERY
 * synchronously on payment, then called {@code delivery.dispatch()}.
 * That conflated two distinct events:
 * <ol>
 *   <li>Payment confirmed → order is PAID.</li>
 *   <li>Courier has picked up the food → order is OUT_FOR_DELIVERY.</li>
 * </ol>
 *
 * <p>Now: payment confirmed → order is PAID, end of story. Dispatch is
 * triggered by the POS transition PAID → IN_KITCHEN (in
 * {@code OrderStatusController}). The order goes OUT_FOR_DELIVERY only
 * when Uber's webhook reports {@code pickup_complete}.
 *
 * <p>Everything else — dedup, locking, refunds, payment-method sync —
 * is unchanged.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private static final int MAX_WEBHOOK_BYTES = 1_000_000;

    private final AppProperties props;
    private final OrderService orderService;
    private final CartService cartService;
    private final PaymentMethodRepository paymentMethods;
    private final UserRepository users;
    private final WebhookEventService webhookEvents;
    private final OrderLockService orderLocks;
    private final OrderEventService orderEvents;
    private final PaymentMetrics metrics;
    private final ApplicationEventPublisher events;
    private final OrderConfirmationEmail orderConfirmationEmail;

    @PostMapping
    public ResponseEntity<String> handle(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature) {

        if (payload == null || payload.length() > MAX_WEBHOOK_BYTES) {
            log.warn("Rejecting oversized Stripe webhook body (size={})",
                    payload == null ? "null" : payload.length());
            return ResponseEntity.status(413).body("Payload too large");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, props.stripe().webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature");
            return ResponseEntity.status(400).body("Invalid signature");
        }

        WebhookEventService.Outcome receipt = webhookEvents.recordReceipt(
                "stripe", event.getId(), event.getType(), payload);
        if (receipt.isDuplicate()) {
            metrics.webhookEvent("stripe", event.getType(), "duplicate");
            return ResponseEntity.ok("duplicate");
        }

        log.info("Stripe webhook: {} ({})", event.getType(), event.getId());

        try {
            dispatch(event);
            if (receipt.isNew()) webhookEvents.markProcessed(receipt.event());
            metrics.webhookEvent("stripe", event.getType(), "processed");
        } catch (Exception e) {
            log.error("Webhook handler failed for {} ({})",
                    event.getType(), event.getId(), e);
            if (receipt.isNew()) webhookEvents.markFailed(receipt.event(), e.getMessage());
            metrics.webhookEvent("stripe", event.getType(), "failed");
            events.publishEvent(new WebhookHandlerFailedEvent(
                    "stripe", event.getId(), event.getType(), e.getMessage()));
            return ResponseEntity.status(500).body("Handler failed");
        }

        return ResponseEntity.ok("ok");
    }

    private void dispatch(Event event) {
        switch (event.getType()) {
            case "payment_intent.succeeded" -> handleIntentSucceeded(event);
            case "payment_method.attached" -> handlePaymentMethodAttached(event);
            case "payment_method.detached" -> handlePaymentMethodDetached(event);
            case "payment_method.updated" -> handlePaymentMethodUpdated(event);
            case "charge.refunded" -> handleRefund(event);
            default -> log.debug("Unhandled event type: {}", event.getType());
        }
    }

    private <T extends StripeObject> T deserialize(Event event, Class<T> type) {
        EventDataObjectDeserializer d = event.getDataObjectDeserializer();
        StripeObject obj = d.getObject().orElseGet(() -> {
            log.debug("Strict deserialize failed for {} — falling back to unsafe",
                    event.getType());
            try {
                return d.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                throw new RuntimeException(e);
            }
        });
        return type.cast(obj);
    }

    // ================================================================
    //  Payment confirmation — marks PAID only. No dispatch.
    // ================================================================

    private void handleIntentSucceeded(Event event) {
        PaymentIntent intent = deserialize(event, PaymentIntent.class);
        // Prefer order_id from metadata — that's the contractual link we set
        // at PaymentIntent creation. Fall back to PI-id lookup for legacy
        // intents that pre-date the metadata stamp.
        String orderIdFromMeta = intent.getMetadata() == null
                ? null : intent.getMetadata().get("order_id");
        Order order = (orderIdFromMeta != null)
                ? orderService.findById(orderIdFromMeta).orElse(null)
                : null;
        if (order == null) {
            order = orderService.findByPaymentIntentId(intent.getId()).orElse(null);
        }
        if (order == null) {
            log.warn("PaymentIntent succeeded for unknown order: pi={} order_id_meta={}",
                    intent.getId(), orderIdFromMeta);
            return;
        }

        final String chargeId = intent.getLatestCharge();
        final String orderId = order.getId();
        orderLocks.withLock(orderId,
                () -> markPaid(orderId, chargeId, "payment_intent.succeeded"));
    }

    private void markPaid(String orderId, String chargeId, String triggerEvent) {
        Order order = orderService.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Order {} disappeared between webhook and lock", orderId);
            return;
        }
        if (chargeId != null && order.getStripeChargeId() == null) {
            order.setStripeChargeId(chargeId);
        }
        if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
            log.info("Order {} already in {}, skipping paid transition",
                    order.getId(), order.getStatus());
            orderService.save(order);
            return;
        }
        Order.Status prev = order.getStatus();
        order.setStatus(Order.Status.PAID);
        order.setPaymentMethod(Order.PaymentMethod.CARD);
        order.setExpiresAt(null);
        orderService.save(order);
        // Detach the order from its originating cart so the next /checkout
        // visit creates a fresh one rather than trying to reuse this (now
        // PAID) order.
        cartService.clearActiveOrderId(orderId);
        metrics.paymentSucceeded();
        orderEvents.record(orderId, prev, Order.Status.PAID,
                "stripe-webhook", null, triggerEvent);

        //All other mailers live in @OrderTransitionService
        //Have to wait on Stripe Webhook response to confirm cards, so this one lives here
        orderConfirmationEmail.send(order);
        log.info("Order {} → PAID (charge={})", order.getId(), chargeId);
    }

    // ================================================================
    //  Refund handling
    // ================================================================

    private void handleRefund(Event event) {
        Charge charge = deserialize(event, Charge.class);
        Order order = orderService.findByChargeId(charge.getId()).orElse(null);
        if (order == null && charge.getPaymentIntent() != null) {
            order = orderService.findByPaymentIntentId(charge.getPaymentIntent()).orElse(null);
        }
        if (order == null) {
            log.warn("charge.refunded for unknown charge: {}", charge.getId());
            return;
        }
        final String orderId = order.getId();

        orderLocks.withLock(orderId, () -> {
            Order o = orderService.findById(orderId).orElse(null);
            if (o == null) return;

            long refunded = charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
            long total = o.getTotalCents();
            boolean fullRefund = refunded >= total;

            String reason = null;
            if (charge.getRefunds() != null && charge.getRefunds().getData() != null
                    && !charge.getRefunds().getData().isEmpty()) {
                Refund latest = charge.getRefunds().getData()
                        .get(charge.getRefunds().getData().size() - 1);
                reason = latest.getReason();
            }

            o.setRefundedAt(Instant.now());
            o.setRefundedAmountCents(refunded);
            o.setRefundReason(reason);
            if (o.getStripeChargeId() == null) o.setStripeChargeId(charge.getId());

            if (fullRefund && o.getStatus() != Order.Status.REFUNDED) {
                Order.Status prev = o.getStatus();
                log.info("Order {} fully refunded ({} cents, reason={}) → REFUNDED",
                        o.getId(), refunded, reason);
                o.setStatus(Order.Status.REFUNDED);
                orderEvents.record(orderId, prev, Order.Status.REFUNDED,
                        "stripe-webhook", null,
                        "refunded " + refunded + " cents (reason=" + reason + ")");
                metrics.paymentRefunded(true);
            } else {
                log.info("Order {} partial refund: {} of {} cents (reason={})",
                        o.getId(), refunded, total, reason);
                metrics.paymentRefunded(false);
            }
            orderService.save(o);
        });
    }

    // ================================================================
    //  PaymentMethod sync (unchanged)
    // ================================================================

    private void handlePaymentMethodAttached(Event event) {
        com.stripe.model.PaymentMethod pm = deserialize(event, com.stripe.model.PaymentMethod.class);
        if (pm.getCustomer() == null) {
            log.debug("PaymentMethod {} attached without customer — ignoring", pm.getId());
            return;
        }
        User user = users.findByStripeCustomerId(pm.getCustomer()).orElse(null);
        if (user == null) {
            log.warn("PaymentMethod attached for unknown Stripe customer: {}", pm.getCustomer());
            return;
        }
        Optional<PaymentMethod> existing = paymentMethods.findByStripePaymentMethodId(pm.getId());
        if (existing.isPresent()) {
            log.debug("PaymentMethod {} already mirrored — skipping", pm.getId());
            return;
        }

        // Mirror only the payment method types we can display meaningfully.
        // Currently that's cards. Link/bank/cashapp/etc. attach silently —
        // Stripe still surfaces them to the customer at checkout via
        // Elements; they just don't appear in our "Saved cards" UI.
        if (!"card".equals(pm.getType())) {
            log.debug("Skipping mirror for non-card PaymentMethod {} (type={})",
                    pm.getId(), pm.getType());
            return;
        }

        PaymentMethod local = PaymentMethod.builder()
                .userId(user.getId())
                .stripePaymentMethodId(pm.getId())
                .type(pm.getType())
                .build();
        if ("card".equals(pm.getType()) && pm.getCard() != null) {
            local.setCardBrand(pm.getCard().getBrand());
            local.setLast4(pm.getCard().getLast4());
            local.setExpMonth(pm.getCard().getExpMonth() == null ? null : pm.getCard().getExpMonth().intValue());
            local.setExpYear(pm.getCard().getExpYear() == null ? null : pm.getCard().getExpYear().intValue());
        }
        boolean isFirst = paymentMethods.findByUserIdOrderByDefaultMethodDescUpdatedAtDesc(user.getId()).isEmpty();
        local.setDefaultMethod(isFirst);
        try {
            paymentMethods.save(local);
            log.info("Mirrored PaymentMethod {} for user {} (default={})",
                    pm.getId(), user.getId(), isFirst);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("Race on PaymentMethod {} — already mirrored by another thread", pm.getId());
        }
    }

    private void handlePaymentMethodDetached(Event event) {
        com.stripe.model.PaymentMethod pm = deserialize(event, com.stripe.model.PaymentMethod.class);
        paymentMethods.findByStripePaymentMethodId(pm.getId()).ifPresent(local -> {
            paymentMethods.delete(local);
            log.info("Removed PaymentMethod {} (was for user {})", pm.getId(), local.getUserId());
        });
    }

    private void handlePaymentMethodUpdated(Event event) {
        com.stripe.model.PaymentMethod pm = deserialize(event, com.stripe.model.PaymentMethod.class);
        paymentMethods.findByStripePaymentMethodId(pm.getId()).ifPresent(local -> {
            if ("card".equals(pm.getType()) && pm.getCard() != null) {
                local.setCardBrand(pm.getCard().getBrand());
                local.setLast4(pm.getCard().getLast4());
                local.setExpMonth(pm.getCard().getExpMonth() == null ? null : pm.getCard().getExpMonth().intValue());
                local.setExpYear(pm.getCard().getExpYear() == null ? null : pm.getCard().getExpYear().intValue());
            }
            paymentMethods.save(local);
            log.info("Updated PaymentMethod {}", pm.getId());
        });
    }
}