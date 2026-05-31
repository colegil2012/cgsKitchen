package com.celtech.solutions.cgsKitchen.delivery.uber;

import com.celtech.solutions.cgsKitchen.config.AppProperties;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen.OrderRepository;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.DeliveryEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderEventService;
import com.celtech.solutions.cgsKitchen.services.storefront.kitchen.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polling fallback for Uber Direct delivery status.
 *
 * <p>Webhooks are the primary signal for status changes, but they can be
 * missed (network blips, app downtime during deploy, NAT issues, or — in
 * sandbox — Uber's simulator stalling). This poller catches stragglers:
 * every {@link #POLL_INTERVAL_MS} milliseconds it pulls all orders that
 * are currently OUT_FOR_DELIVERY with provider=uber, fetches each
 * delivery's current state from Uber, and applies the same mapping the
 * webhook controller uses.
 *
 * <p>The poller is bounded by {@link #BATCH_SIZE} so a backlog can't
 * stampede Uber's rate limit (200 req/10min in sandbox per app).
 *
 * <p>Activates only when {@code app.delivery.provider=uber}. With any
 * other provider this bean is registered but does nothing on each tick.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UberDeliveryPoller {

    /** Every 2 minutes. */
    private static final long POLL_INTERVAL_MS = 120_000L;

    /** Cap per cycle; protects against rate limits and runaway batches. */
    private static final int BATCH_SIZE = 25;

    private final AppProperties props;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final UberDirectClient client;
    private final OrderEventService orderEvents;
    private final DeliveryEventService deliveryEvents;

    @Scheduled(fixedDelay = POLL_INTERVAL_MS, initialDelay = 30_000L)
    public void poll() {
        if (!props.delivery().isUber()) {
            return;  // poller is no-op for other providers
        }

        List<Order> active = orderRepository.findByStatusAndDeliveryProvider(
                Order.Status.OUT_FOR_DELIVERY, "uber");

        if (active.isEmpty()) {
            log.debug("Uber poll: no active deliveries");
            return;
        }

        // Trim to BATCH_SIZE so we don't blast Uber on a backlog.
        List<Order> batch = active.size() > BATCH_SIZE
                ? active.subList(0, BATCH_SIZE)
                : active;

        log.info("Uber poll: checking {} active deliver{}",
                batch.size(), batch.size() == 1 ? "y" : "ies");

        int updated = 0;
        int errors = 0;
        for (Order order : batch) {
            try {
                if (refreshOne(order)) updated++;
            } catch (Exception e) {
                errors++;
                log.warn("Uber poll: failed to refresh order {} (delivery {})",
                        order.getId(), order.getDeliveryExternalId(), e);
            }
        }

        if (updated > 0 || errors > 0) {
            log.info("Uber poll done: updated={} errors={} skipped={}",
                    updated, errors, batch.size() - updated - errors);
        }
    }

    /**
     * Pull the delivery from Uber, apply the status mapping, persist if changed.
     * @return true if the order was modified.
     */
    private boolean refreshOne(Order order) throws Exception {
        String deliveryId = order.getDeliveryExternalId();
        if (deliveryId == null || deliveryId.isBlank()) {
            log.warn("Order {} has no deliveryExternalId; skipping", order.getId());
            return false;
        }

        UberDirectClient.DeliveryResponse d = client.getDelivery(deliveryId);

        // Always record telemetry for the poll observation.
        deliveryEvents.record(order.getId(), deliveryId, "uber", d.status(),
                "poll_observation", null);

        UberDeliveryStatusMapper.Decision decision =
                UberDeliveryStatusMapper.decide(d.status(), order.getStatus());

        boolean changed = false;

        if (decision.changesStatus()) {
            Order.Status prev = order.getStatus();
            log.info("Uber poll: order {} {} → {} (uber status={})",
                    order.getId(), prev, decision.nextStatus(), d.status());
            order.setStatus(decision.nextStatus());
            orderEvents.record(order.getId(), prev, decision.nextStatus(),
                    "uber-poll", null, "uber status=" + d.status());
            changed = true;
        }

        if (decision.markAttentionRequired() && !order.isDeliveryAttentionRequired()) {
            order.setDeliveryAttentionRequired(true);
            log.info("Uber poll: order {} delivery attention required (uber status={})",
                    order.getId(), d.status());
            changed = true;
        }

        if (d.trackingUrl() != null && order.getDeliveryTrackingUrl() == null) {
            order.setDeliveryTrackingUrl(d.trackingUrl());
            changed = true;
        }

        if (changed) {
            orderService.save(order);
        }
        return changed;
    }
}