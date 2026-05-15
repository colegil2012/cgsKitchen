package com.celtech.solutions.cgsKitchen.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * An order is the unit of revenue. Created when a customer initiates
 * checkout (via storefront or POS) and progresses through statuses as
 * payment / fulfillment events occur.
 *
 * <p>Money values are stored in cents to avoid floating-point issues.
 */
@Document(collection = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    private String id;

    /** Multi-tenant scoping. */
    @Indexed
    private String clientId;

    /** Source channel — for analytics. */
    private Source source;

    @Indexed
    private Status status;

    private Fulfillment fulfillment;

    /** Customer info, populated as the flow progresses. */
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    /** Where the order is going (delivery only). */
    private String deliveryAddress;

    private List<LineItem> items;

    private long subtotalCents;
    private long taxCents;
    private long deliveryFeeCents;
    private long tipCents;
    private long totalCents;

    /** Stripe references — populated as payment progresses. */
    private String stripePaymentIntentId;
    private String stripeCheckoutSessionId;
    private String stripeChargeId;

    /** Delivery provider references. */
    private String deliveryProvider;          // doordash | uber
    private String deliveryExternalId;        // provider's delivery ID
    private String deliveryTrackingUrl;

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    public enum Source { WEB, POS, KIOSK }
    public enum Status {
        PENDING_PAYMENT,
        PAID,
        IN_KITCHEN,
        READY,
        OUT_FOR_DELIVERY,
        COMPLETED,
        CANCELLED,
        REFUNDED
    }
    public enum Fulfillment { PICKUP, DELIVERY, DINE_IN }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineItem {
        private String menuItemId;
        private String name;
        private int quantity;
        private long unitPriceCents;
        private List<String> modifiers;
        private String notes;
    }
}
