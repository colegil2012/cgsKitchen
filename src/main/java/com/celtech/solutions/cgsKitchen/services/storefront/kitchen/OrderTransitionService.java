package com.celtech.solutions.cgsKitchen.services.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.delivery.DeliveryProvider;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.webhooks.OrderLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Single entry point for every order state change in the system.
 *
 * <p><strong>Why this exists:</strong> previously the system had four
 * different paths that mutated {@code Order.status} — the API endpoint
 * for POS, the admin web controller, the Stripe webhook handler, and
 * the Uber webhook/poller. Each path had its own subset of side
 * effects (transition matrix check, dispatch hook, audit log, lock).
 * They diverged: the admin path skipped everything, the API path did
 * the matrix + dispatch + audit, the webhooks did locking + audit but
 * bypassed the matrix.
 *
 * <p>Now: every caller funnels through {@link #transition}. Behavior
 * is uniform. Differences between callers (human-driven vs webhook
 * observation, matrix-enforced vs not, dispatch eligibility) are
 * expressed via parameters, not separate code paths.
 *
 * <p>{@code DeliveryProvider} is injected with {@code @Autowired(required=false)}
 * so this service still works when no delivery provider is configured —
 * useful for tests and for tenants who run pickup-only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransitionService {

    private final OrderService orderService;
    private final OrderEventService orderEvents;
    private final DeliveryEventService deliveryEvents;
    private final OrderLockService orderLocks;
    private final AppProperties props;

    /** Optional — null in test profiles or no-delivery deployments. */
    @Autowired(required = false)
    private DeliveryProvider delivery;

    /**
     * Apply a status transition with the appropriate side effects.
     *
     * <p>Side effects (in order):
     * <ol>
     *   <li>Lock the order id</li>
     *   <li>Re-read the order under the lock</li>
     *   <li>If {@code enforceMatrix}, validate the transition via
     *       {@link OrderStateTransitions#rejectionReason}</li>
     *   <li>Set status and persist via {@code OrderService.save} (which
     *       clears {@code expiresAt} on non-PENDING transitions)</li>
     *   <li>Record an {@code order_events} row attributing the change</li>
     *   <li>If the transition is PAID → IN_KITCHEN on a delivery order
     *       without a courier yet, call the delivery provider to
     *       dispatch one</li>
     * </ol>
     *
     * @param orderId        the order to transition
     * @param target         the desired new status
     * @param source         "pos", "admin", "stripe-webhook", "uber-webhook", "uber-poll"
     * @param actor          optional identifier of who/what initiated this
     * @param note           optional free-form context for the audit log
     * @param enforceMatrix  true for human-driven (POS, admin); false for
     *                       external observations (webhooks, poller) that
     *                       report what already happened
     * @return result describing the outcome
     */
    public TransitionResult transition(String orderId, Order.Status target,
                                       String source, String actor, String note,
                                       boolean enforceMatrix) {

        TransitionResult result = orderLocks.withLock(orderId, () -> {
            Order order = orderService.findById(orderId).orElse(null);
            if (order == null) {
                return TransitionResult.notFound(orderId);
            }

            if (enforceMatrix) {
                String rejection = OrderStateTransitions.rejectionReason(order, target);
                if (rejection != null) {
                    log.warn("Order {} transition rejected ({}→{}): {}",
                            orderId, order.getStatus(), target, rejection);
                    return TransitionResult.rejected(order, rejection);
                }
            } else if (target == order.getStatus()) {
                // Webhook reported the status we're already in — no-op,
                // but still legal.
                return TransitionResult.noChange(order);
            }

            Order.Status previous = order.getStatus();
            order.setStatus(target);
            Order saved = orderService.save(order);

            orderEvents.record(orderId, previous, target, source, actor, note);
            log.info("Order {} : {} → {} ({}{})",
                    orderId, previous, target, source,
                    actor == null ? "" : " by " + actor);

            // Dispatch hook — PAID → IN_KITCHEN on a delivery order with no
            // courier yet means "kitchen is starting; summon a courier in parallel".
            boolean dispatched = false;
            if (target == Order.Status.IN_KITCHEN) {
                dispatched = maybeDispatchDelivery(saved, actor);
            }

            return TransitionResult.success(saved, previous, dispatched);
        });

        return result;
    }

    /**
     * Convenience overload — defaults {@code enforceMatrix} to true. Use
     * this from human-driven callers (POS API, admin web). Webhooks
     * should pass false explicitly.
     */
    public TransitionResult transition(String orderId, Order.Status target,
                                       String source, String actor, String note) {
        return transition(orderId, target, source, actor, note, true);
    }

    /**
     * Force-clear delivery references and re-dispatch. Used when Uber
     * cancels a delivery mid-flight and the kitchen wants to try again
     * (different courier, different time). Does NOT change order status.
     *
     * <p>Calls {@link DeliveryProvider#dispatch} after wiping the prior
     * external id, tracking URL, attention flag, and cancellation reason.
     */
    public TransitionResult redispatch(String orderId, String actor) {
        TransitionResult result = orderLocks.withLock(orderId, () -> {
            Order order = orderService.findById(orderId).orElse(null);
            if (order == null) {
                return TransitionResult.notFound(orderId);
            }
            if (order.getFulfillment() != Order.Fulfillment.DELIVERY) {
                return TransitionResult.rejected(order, "Order is not a delivery order");
            }
            if (order.getStatus() == Order.Status.COMPLETED
                    || order.getStatus() == Order.Status.CANCELLED
                    || order.getStatus() == Order.Status.REFUNDED) {
                return TransitionResult.rejected(order,
                        "Cannot redispatch — order is in " + order.getStatus());
            }

            String previousDeliveryId = order.getDeliveryExternalId();
            order.setDeliveryExternalId(null);
            order.setDeliveryTrackingUrl(null);
            order.setDeliveryAttentionRequired(false);
            order.setCancellationReason(null);
            order.setCancellationDetail(null);
            orderService.save(order);

            deliveryEvents.record(orderId, previousDeliveryId,
                    delivery == null ? "none" : delivery.name(),
                    null, "redispatch_request",
                    "redispatched by " + (actor == null ? "unknown" : actor));

            boolean dispatched = maybeDispatchDelivery(order, actor);
            Order refreshed = orderService.findById(orderId).orElse(order);
            return TransitionResult.success(refreshed, order.getStatus(), dispatched);
        });
        return result;
    }

    // ================================================================
    //  Internal — dispatch helper
    // ================================================================

    /**
     * Summon a courier for a delivery order. Returns true on success.
     * Failures are logged + recorded to delivery_events but never throw —
     * the kitchen keeps cooking regardless.
     */
    private boolean maybeDispatchDelivery(Order order, String actor) {
        if (order.getFulfillment() != Order.Fulfillment.DELIVERY) {
            log.debug("Order {} is {} — no dispatch needed",
                    order.getId(), order.getFulfillment());
            return false;
        }
        if (order.getDeliveryExternalId() != null) {
            log.debug("Order {} already has delivery {} — skipping re-dispatch",
                    order.getId(), order.getDeliveryExternalId());
            return false;
        }
        if (order.getDeliveryAddress() == null || order.getDeliveryAddress().isBlank()) {
            log.warn("Order {} is delivery but has no delivery address — cannot dispatch",
                    order.getId());
            return false;
        }
        if (delivery == null) {
            log.warn("Order {} would dispatch but no DeliveryProvider is configured",
                    order.getId());
            return false;
        }

        try {
            var d = delivery.dispatch(new DeliveryProvider.DispatchRequest(
                    order.getId(),
                    props.delivery().pickupAddress(),
                    order.getDeliveryAddress(),
                    order.getCustomerName() == null ? "Customer" : order.getCustomerName(),
                    order.getCustomerPhone() == null ? "" : order.getCustomerPhone(),
                    order.getTotalCents(),
                    order.getTipCents()
            ));
            order.setDeliveryProvider(delivery.name());
            order.setDeliveryExternalId(d.externalDeliveryId());
            order.setDeliveryTrackingUrl(d.trackingUrl());
            orderService.save(order);

            deliveryEvents.record(order.getId(), d.externalDeliveryId(),
                    delivery.name(), d.status(),
                    "dispatch_success",
                    "dispatched by " + (actor == null ? "system" : actor));

            log.info("Order {} dispatched via {} ({})",
                    order.getId(), delivery.name(), d.externalDeliveryId());
            return true;

        } catch (Exception e) {
            log.error("Delivery dispatch failed for order {}", order.getId(), e);
            deliveryEvents.record(order.getId(), null,
                    delivery == null ? "none" : delivery.name(),
                    null, "dispatch_failure", e.getMessage());
            return false;
        }
    }

    /**
     * Confirm a point-of-sale cash payment. The POS is the payment
     * authority for cash, so this reports a completed payment the same way
     * the Stripe webhook does — it does NOT go through the kitchen matrix
     * (PENDING_PAYMENT has no legal matrix transitions by design). Only
     * legal from PENDING_PAYMENT; anything else is rejected so this can't be
     * used to bypass refund/cancel rules.
     */
    public TransitionResult confirmCashPayment(String orderId, String actor, String note) {
        return orderLocks.withLock(orderId, () -> {
            Order order = orderService.findById(orderId).orElse(null);
            if (order == null) {
                return TransitionResult.notFound(orderId);
            }
            if (order.getStatus() == Order.Status.PAID) {
                return TransitionResult.noChange(order); // idempotent — safe on queue retry
            }
            if (order.getStatus() != Order.Status.PENDING_PAYMENT) {
                return TransitionResult.rejected(order,
                        "Cash payment only valid from PENDING_PAYMENT; order is "
                                + order.getStatus());
            }

            Order.Status previous = order.getStatus();
            order.setStatus(Order.Status.PAID);
            Order saved = orderService.save(order);

            orderEvents.record(orderId, previous, Order.Status.PAID,
                    "pos", actor, note == null ? "cash payment" : note);
            log.info("Order {} : {} → PAID (pos cash{})",
                    orderId, previous, actor == null ? "" : " by " + actor);

            return TransitionResult.success(saved, previous, false);
        });
    }

    // ================================================================
    //  Result type
    // ================================================================

    public record TransitionResult(
            Outcome outcome,
            Order order,
            Order.Status previousStatus,
            String message,
            boolean dispatched
    ) {
        public enum Outcome { SUCCESS, NO_CHANGE, REJECTED, NOT_FOUND }

        public boolean isSuccess() { return outcome == Outcome.SUCCESS; }
        public boolean isRejected() { return outcome == Outcome.REJECTED; }
        public boolean isNotFound() { return outcome == Outcome.NOT_FOUND; }

        static TransitionResult success(Order o, Order.Status prev, boolean dispatched) {
            return new TransitionResult(Outcome.SUCCESS, o, prev, null, dispatched);
        }
        static TransitionResult noChange(Order o) {
            return new TransitionResult(Outcome.NO_CHANGE, o, o.getStatus(), null, false);
        }
        static TransitionResult rejected(Order o, String reason) {
            return new TransitionResult(Outcome.REJECTED, o, o.getStatus(), reason, false);
        }
        static TransitionResult notFound(String orderId) {
            return new TransitionResult(Outcome.NOT_FOUND, null, null,
                    "Order " + orderId + " not found", false);
        }
    }
}