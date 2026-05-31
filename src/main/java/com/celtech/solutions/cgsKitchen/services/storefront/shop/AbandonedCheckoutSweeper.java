package com.celtech.solutions.cgsKitchen.services.storefront.shop;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sweeps abandoned checkout sessions.
 *
 * <p>Orders that enter {@code PENDING_PAYMENT} and never get paid would
 * otherwise:
 * <ul>
 *   <li>TTL out of Mongo (good — handled by {@code orders.expiresAt})</li>
 *   <li>Leave the Stripe PaymentIntent in {@code requires_payment_method}
 *       status forever (bad — clutters the Stripe dashboard and skews
 *       conversion metrics)</li>
 * </ul>
 *
 * <p>This job runs periodically, finds {@code PENDING_PAYMENT} orders
 * older than {@link #ABANDON_AFTER}, and cancels their Stripe
 * PaymentIntents. It does <em>not</em> delete the Order row itself —
 * the Mongo TTL on {@code orders.expiresAt} handles eventual cleanup,
 * and keeping the row around briefly lets us correlate any late
 * {@code payment_intent.canceled} webhook back to it.
 *
 * <p>Single-node sweeper. If you scale horizontally, two pods will run
 * the same sweep concurrently — that's fine here (Stripe cancel is
 * idempotent enough; the second pod's calls will no-op because the PI
 * is already in {@code canceled} status), but if you want strict
 * single-execution semantics, gate this with ShedLock or similar.
 *
 * <p>Manual ad-hoc invocation: call {@link #sweep()} directly from an
 * admin endpoint if you ever need to force a cleanup pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbandonedCheckoutSweeper {

    /**
     * How long a PENDING_PAYMENT order can sit before we assume the
     * customer walked away. Tuned to be longer than typical checkout
     * dwell time (30 min covers a slow customer + a tab left open over
     * lunch) but well short of the Mongo TTL on {@code orders.expiresAt}
     * (30 days), so we always get a chance to cancel the PI before the
     * order row vanishes.
     */
    private static final Duration ABANDON_AFTER = Duration.ofMinutes(30);

    private final OrderService orderService;
    private final CartService cartService;
    private final CheckoutService checkoutService;

    /**
     * Runs every 5 minutes. {@code fixedDelay} (not {@code fixedRate})
     * so a long sweep can't pile up overlapping runs.
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES, initialDelay = 1)
    public void sweep() {
        Instant cutoff = Instant.now().minus(ABANDON_AFTER);
        List<Order> abandoned = orderService.findPendingPaymentOlderThan(cutoff);
        if (abandoned.isEmpty()) {
            log.debug("Abandoned-checkout sweep: nothing to do (cutoff={})", cutoff);
            return;
        }

        int cancelled = 0;
        int skipped = 0;
        for (Order o : abandoned) {
            if (o.getStripePaymentIntentId() == null) {
                // Order without a PI — nothing to cancel on Stripe's side.
                // Will TTL out of Mongo on its own. Still detach it from
                // its cart so the customer can start a fresh checkout.
                cartService.clearActiveOrderId(o.getId());
                skipped++;
                continue;
            }
            String result = checkoutService.cancelPaymentIntent(o.getStripePaymentIntentId());
            if ("canceled".equals(result)) {
                // Successfully cancelled — release the cart pointer so the
                // customer's next visit creates a new order rather than
                // attempting to reuse this dead one.
                cartService.clearActiveOrderId(o.getId());
                cancelled++;
            } else {
                skipped++;
            }
        }
        log.info("Abandoned-checkout sweep: examined {}, cancelled {}, skipped {} (cutoff={})",
                abandoned.size(), cancelled, skipped, cutoff);
    }
}