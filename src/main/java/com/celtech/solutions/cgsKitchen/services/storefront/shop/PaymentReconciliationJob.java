package com.celtech.solutions.cgsKitchen.services.storefront.shop;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import com.celtech.solutions.cgsKitchen.services.webhooks.OrderLockService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentIntentCollection;
import com.stripe.param.PaymentIntentListParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Nightly safety-net that catches orders which were paid on Stripe but
 * never transitioned to PAID locally — i.e. a {@code payment_intent.succeeded}
 * webhook that was never successfully processed (Stripe exhausted its
 * retries, or the app was down for the entire retry window).
 *
 * <p>Stripe's webhook delivery is highly reliable, so this should
 * almost always be a no-op. When it isn't, it logs loudly with a
 * {@code [RECONCILE]} marker (alert on it the same way as
 * {@code [WEBHOOK-ALERT]}) and self-heals by transitioning the order
 * through the same idempotent, locked path the webhook uses.
 *
 * <p>Window: PaymentIntents created in the last 26 hours. The job runs
 * daily at 03:30; 26h overlaps two consecutive runs so an intent created
 * right after one run's window closes is still caught by the next.
 *
 * <p>Read-mostly: for the overwhelming-common case (everything already
 * reconciled) it makes one Stripe list call per 100 intents and zero
 * writes. Cheap enough to run daily without ceremony.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationJob {

    private static final Duration LOOKBACK = Duration.ofHours(26);

    private final AppProperties props;
    private final OrderService orderService;
    private final OrderLockService orderLocks;
    private final OrderEventService orderEvents;

    @Scheduled(cron = "${app.reconcile.cron:0 30 3 * * *}")
    public void reconcile() {
        if (!props.stripe().isConfigured()) {
            log.debug("Reconciliation skipped — Stripe not configured");
            return;
        }

        long createdGte = Instant.now().minus(LOOKBACK).getEpochSecond();
        int examined = 0;
        int healed = 0;
        int mismatched = 0;

        try {
            PaymentIntentListParams params = PaymentIntentListParams.builder()
                    .setCreated(PaymentIntentListParams.Created.builder()
                            .setGte(createdGte)
                            .build())
                    .setLimit(100L)
                    .build();

            PaymentIntentCollection page = PaymentIntent.list(params);
            // auto-paginate across all pages in the window
            for (PaymentIntent pi : page.autoPagingIterable()) {
                examined++;
                if (!"succeeded".equals(pi.getStatus())) {
                    continue;
                }

                // Resolve the local order: prefer order_id metadata, fall
                // back to PI-id lookup (mirrors the webhook's resolution).
                String orderId = pi.getMetadata() == null
                        ? null : pi.getMetadata().get("order_id");
                Order order = (orderId != null)
                        ? orderService.findById(orderId).orElse(null)
                        : null;
                if (order == null) {
                    order = orderService.findByPaymentIntentId(pi.getId()).orElse(null);
                }
                if (order == null) {
                    // Succeeded on Stripe but we have no record at all — this
                    // is a genuine anomaly worth a human looking at it.
                    log.error("[RECONCILE] Succeeded PaymentIntent {} has no matching order " +
                            "(order_id meta={})", pi.getId(), orderId);
                    mismatched++;
                    continue;
                }

                if (order.getStatus() == Order.Status.PENDING_PAYMENT) {
                    final String fOrderId = order.getId();
                    final String chargeId = pi.getLatestCharge();
                    log.error("[RECONCILE] Order {} is PENDING_PAYMENT but PaymentIntent {} " +
                            "succeeded on Stripe — healing to PAID", fOrderId, pi.getId());
                    orderLocks.withLock(fOrderId, () -> {
                        boolean changed = orderService.markPaidIfPending(fOrderId, chargeId);
                        if (changed) {
                            orderEvents.record(fOrderId, Order.Status.PENDING_PAYMENT,
                                    Order.Status.PAID, "reconciliation", null,
                                    "healed from Stripe PI " + pi.getId());
                        }
                    });
                    healed++;
                }
                // Orders already PAID/beyond: nothing to do.
            }
        } catch (StripeException e) {
            log.error("[RECONCILE] Stripe list call failed — reconciliation incomplete", e);
            return;
        }

        if (healed > 0 || mismatched > 0) {
            log.warn("[RECONCILE] complete: examined={}, healed={}, unmatched={}",
                    examined, healed, mismatched);
        } else {
            log.info("Reconciliation complete: examined {} intents, all consistent", examined);
        }
    }
}