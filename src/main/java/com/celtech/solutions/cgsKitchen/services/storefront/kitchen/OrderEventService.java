package com.celtech.solutions.cgsKitchen.services.storefront.kitchen;

import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.OrderEvent;
import com.celtech.solutions.cgsKitchen.models.storefront.kitchen.Order;
import com.celtech.solutions.cgsKitchen.repositories.storefront.kitchen.OrderEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Append-only writer for the order-event audit log.
 *
 * <p>Use {@link #record} at every place an order's status mutates —
 * webhook controllers, POS endpoint, admin actions, scheduled jobs.
 * Centralizing here means we get a complete history and a single
 * point to extend later (notification triggers, metrics, etc.).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventService {

    private final OrderEventRepository repo;

    /**
     * Record a state transition. Safe to call even when {@code from} and
     * {@code to} are the same (no-op transitions still go in the log for
     * forensic purposes).
     *
     * @param orderId  the order being changed
     * @param from     previous status (null for initial events)
     * @param to       new status
     * @param source   "pos", "stripe-webhook", "uber-webhook", "uber-poll", "admin"
     * @param actor    user or system identifier; null is acceptable
     * @param detail   free-form context (e.g. "uber status=delivered", "refund 2500 cents")
     */
    public OrderEvent record(String orderId, Order.Status from, Order.Status to,
                             String source, String actor, String detail) {
        try {
            OrderEvent evt = OrderEvent.builder()
                    .orderId(orderId)
                    .fromStatus(from)
                    .toStatus(to)
                    .source(source)
                    .actor(actor)
                    .detail(detail)
                    .occurredAt(Instant.now())
                    .build();
            return repo.save(evt);
        } catch (Exception e) {
            // Audit log failures must NEVER block the primary mutation.
            log.error("Failed to record order event for {}: {} → {} ({})",
                    orderId, from, to, source, e);
            return null;
        }
    }

    public List<OrderEvent> historyFor(String orderId) {
        return repo.findByOrderIdOrderByOccurredAtAsc(orderId);
    }
}