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
 * High-frequency delivery telemetry — every Uber webhook arrives here
 * regardless of whether it transitions the order status.
 *
 * <p>This is intentionally separate from {@link OrderEvent} (which holds
 * meaningful order state transitions) because Uber sends courier
 * position updates every 20 seconds during transit. Mixing those into
 * order_events would make the audit log unreadable.
 *
 * <p>Both collections are indexed by orderId so a "show me everything
 * that happened to this order" query joins them cleanly.
 *
 * <p>TTL: 14 days. Position data has no long-term value; the meaningful
 * transitions are already permanent in order_events.
 */
@Document(collection = "delivery_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryEvent {

    public static final int TTL_DAYS = 14;

    @Id
    private String id;

    @Indexed
    private String orderId;

    @Indexed
    private String deliveryExternalId;

    /** Provider — "uber", "doordash" (future), "mock". */
    private String provider;

    /** The raw status string from the provider, e.g. "pending", "pickup", "pickup_complete". */
    private String providerStatus;

    /** Event kind — "delivery_status", "courier_update", "dispatch_attempt", etc. */
    private String kind;

    /** Free-form context — courier name, location, cancellation reason, etc. */
    private String detail;

    /** Latitude (for courier updates). */
    private Double lat;

    /** Longitude (for courier updates). */
    private Double lng;

    @Indexed
    private Instant occurredAt;
}