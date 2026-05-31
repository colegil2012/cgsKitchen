package com.celtech.solutions.cgsKitchen.models.storefront.kitchen;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Append-only audit log of every order state transition.
 *
 * <p>Sourced from multiple paths — POS endpoints, Stripe webhooks, Uber
 * webhooks, the delivery poller, admin overrides. Each entry records what
 * changed, who/what made the change, and any free-form detail.
 *
 * <p>This collection is invaluable when reconstructing "why did this
 * order end up in state X" days or weeks later, especially when multiple
 * actors (kitchen, customer, automated systems) all touch the same order.
 */
@Document(collection = "order_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {

    @Id
    private String id;

    @Indexed
    private String orderId;

    /** Where the change came from — "pos", "stripe-webhook", "uber-webhook", "uber-poll", "admin". */
    private String source;

    /** Previous status (may be null for the very first event of an order). */
    private Order.Status fromStatus;

    /** New status. */
    private Order.Status toStatus;

    /** Free-form detail — e.g. the Uber status string, the refund amount, etc. */
    private String detail;

    /** Username or system identifier of whoever drove the change. */
    private String actor;

    @Indexed
    private Instant occurredAt;
}