package com.celtech.solutions.cgsKitchen.models.user;

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

/**
 * A mirror of a Stripe PaymentMethod attached to a user's Stripe Customer.
 *
 * <p>We store <em>only</em> tokenized references and harmless display
 * metadata (last4, brand, expiry). Real card numbers and CVVs never
 * touch our server — Stripe holds them. A breach of this collection
 * exposes nothing chargeable.
 *
 * <p>Synced via Stripe webhooks: {@code payment_method.attached},
 * {@code payment_method.detached}, {@code payment_method.updated}.
 */
@Document(collection = "payment_methods")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethod {

    @Id
    private String id;

    @Indexed
    private String userId;

    /** Stripe PaymentMethod id (pm_...). */
    @Indexed(unique = true)
    private String stripePaymentMethodId;

    /** "card", "us_bank_account", "link", etc. */
    private String type;

    private String cardBrand;   // "visa", "mastercard", ...
    private String last4;
    private Integer expMonth;
    private Integer expYear;

    @Builder.Default
    private boolean defaultMethod = false;

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    public String displayName() {
        if ("card".equals(type) && cardBrand != null) {
            return capitalize(cardBrand) + " •••• " + last4;
        }
        return type + " •••• " + last4;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}