package com.celtech.solutions.cgsKitchen.models.storefront.kitchen;

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
 * Order — the unit of revenue. See package-info for the full lifecycle.
 *
 * <p><b>Delivery-attention pattern:</b> when Uber cancels a delivery
 * mid-flight (courier no-show, address invalid, etc.), we do NOT
 * auto-transition the order to CANCELLED — the food is paid for and
 * often partly cooked. Instead we set {@link #deliveryAttentionRequired}
 * true and capture the cancellation reason. Kitchen staff are alerted in
 * the POS UI and choose: redispatch (retry Uber), cancel the order
 * (refund customer), or convert to customer pickup.
 *
 * <p><b>{@code promisedReadyAt}:</b> captured at order creation time as
 * the "we will have your food ready by this instant" guarantee. Reflects
 * the kitchen quote *at the moment of checkout* — used for late-detection
 * in the admin UI ("this order is 5 min behind promise"). Never updated
 * after creation; the original promise is permanent.
 */
@Document(collection = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    private String id;

    private Source source;

    @Indexed
    private Status status;

    @Indexed
    private String eventId;

    private Fulfillment fulfillment;

    @Indexed
    private String userId;

    private String customerName;
    private String customerEmail;
    private String customerPhone;

    private String deliveryAddress;

    private DeliveryQuote currentDeliveryQuote;

    private List<LineItem> items;

    private long subtotalCents;
    private long taxCents;
    private long deliveryFeeCents;
    private long tipCents;
    private long totalCents;

    @Indexed
    private PaymentMethod paymentMethod;

    private String stripePaymentIntentId;
    private String stripeCheckoutSessionId;
    private String stripeChargeId;

    private String deliveryProvider;
    private String deliveryExternalId;
    private String deliveryTrackingUrl;

    private String cancellationReason;
    private String cancellationDetail;

    @Indexed
    private boolean deliveryAttentionRequired;

    private Instant refundedAt;
    private Long refundedAmountCents;
    private String refundReason;

    @Indexed
    private Instant expiresAt;

    /** When we promised this order would be ready. Stamped at checkout. */
    private Instant promisedReadyAt;

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
    public enum PaymentMethod { UNPAID, CASH, CARD, OTHER }

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryQuote {
        private String quoteId;       // server-generated UUID
        private String address;       // address the quote was for
        private long feeCents;
        private int etaMinutes;
        private String provider;
        private Instant issuedAt;
        private Instant expiresAt;    // typically issuedAt + 10 min
    }
}