package com.celtech.solutions.cgsKitchen.models.storefront.shop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The customer's shopping cart. Persisted in Mongo, one per signed-in
 * user (keyed by {@code userId}) or one per guest browser (keyed by
 * {@code cookieId}).
 *
 * <p>At any moment exactly one of {@code userId} or {@code cookieId} is
 * populated. When a guest signs in, the merge process sets
 * {@code userId} and deletes the guest row — the cart "ascends" from
 * the guest pool into the user's collection.
 *
 * <p>Mutation lives in
 * {@code com.celtech.solutions.cgsKitchen.services.storefront.shop.CartService}.
 * This class is a data carrier; callers go through the service for
 * add/remove/update/clear so persistence happens transactionally.
 *
 * <h2>History</h2>
 * Previously there were two classes: an in-session {@code Cart} POJO
 * (the session-scoped bean) and a {@code PersistentCart} Mongo document.
 * The session bean is gone (replaced by the cookie + DB model); this
 * class is the renamed PersistentCart with the inner
 * {@link CartLine} / {@link SelectedOption} types absorbed.
 *
 * <h2>TTL for guest carts</h2>
 * {@code expiresAt} is set 30 days out for guest carts (where
 * {@code userId == null}) and is null for user carts. The Mongo TTL
 * index on {@code expiresAt} reaps guest carts automatically; user
 * carts persist indefinitely.
 */
@Document(collection = "carts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cart {

    @Id
    private String id;

    /**
     * Owning user id. Null for guest carts. When set, {@code cookieId}
     * must be null. Unique sparse index ensures one cart per user.
     */
    @Indexed(unique = true, sparse = true)
    private String userId;

    /**
     * Browser-cookie identifier for guest carts. Null once the cart has
     * been bound to a user. Unique sparse index ensures one cart per
     * cookie at a time.
     */
    @Indexed(unique = true, sparse = true)
    private String cookieId;

    /**
     * The id of the in-flight {@code PENDING_PAYMENT} Order for this cart,
     * if any. Set when the visitor first reaches {@code /checkout}; cleared
     * when the order transitions out of PENDING_PAYMENT (paid, cancelled,
     * refunded) or when it expires.
     *
     * <p>This replaces the previous session-scoped {@code CheckoutSession}
     * pointer — the cart-bound id survives login, session rotation, and
     * multi-device access for signed-in users, which is what we want
     * (one cart → at most one in-flight order at a time).
     */
    private String activeOrderId;

    /** Cart line items. Each line is one product + selections + quantity. */
    @Builder.Default
    private List<CartLine> lines = new ArrayList<>();

    /**
     * For guest carts: when this cart should be auto-deleted by the
     * Mongo TTL index. Null for user carts (no expiration).
     * Refreshed on each modification by CartService (rolling 30-day
     * window).
     */
    @Indexed(expireAfter = "7d")
    private Instant expiresAt;

    @CreatedDate private Instant createdAt;
    @LastModifiedDate private Instant updatedAt;

    /** True if this cart belongs to a signed-in user. */
    public boolean isUserCart() {
        return userId != null && !userId.isBlank();
    }

    /** True if this cart belongs to a guest browser. */
    public boolean isGuestCart() {
        return !isUserCart() && cookieId != null && !cookieId.isBlank();
    }

    public boolean isEmpty() {
        return lines == null || lines.isEmpty();
    }

    /** Total quantity across all lines — what the nav badge displays. */
    public int getItemCount() {
        if (lines == null) return 0;
        return lines.stream().mapToInt(CartLine::getQuantity).sum();
    }

    /** Sum of line totals (unit price × quantity per line). Used for tax math. */
    public long getSubtotalCents() {
        if (lines == null) return 0L;
        return lines.stream().mapToLong(CartLine::getLineTotalCents).sum();
    }

    /** Display variant for templates ($X.XX). */
    public String getSubtotalDisplay() {
        return String.format("$%.2f", getSubtotalCents() / 100.0);
    }

    // ================================================================
    //  Embedded types — moved here from the old session Cart class.
    //  These are stored as embedded documents inside the `carts`
    //  collection. Keep them stable in shape; renaming or restructuring
    //  requires data migration.
    // ================================================================

    /**
     * One row in a cart — a specific menu item with specific selections
     * and a quantity.
     *
     * <p>{@code lineId} is a UUID generated at add-time so updates and
     * removes can target a specific row even when the customer has the
     * same item with different selections (e.g. "Boxty w/ bacon" and
     * "Boxty w/ sausage" stay separate lines).
     *
     * <p>Implements {@link Serializable} for legacy compatibility; not
     * required for Mongo storage but harmless and avoids surprises if
     * anything else in the codebase relies on it.
     */
    @Data
    @NoArgsConstructor
    public static class CartLine implements Serializable {
        /** Stable id for this specific cart line (so updates target the right row). */
        private String lineId;
        private String menuItemId;
        private String name;
        /** Final unit price the customer pays (base + selection upcharges). */
        private long unitPriceCents;
        private int quantity;
        private List<SelectedOption> selections = new ArrayList<>();

        public CartLine(String lineId, String menuItemId, String name,
                        long unitPriceCents, int quantity,
                        List<SelectedOption> selections) {
            this.lineId = lineId;
            this.menuItemId = menuItemId;
            this.name = name;
            this.unitPriceCents = unitPriceCents;
            this.quantity = quantity;
            this.selections = selections == null ? new ArrayList<>() : new ArrayList<>(selections);
        }

        public long getLineTotalCents() {
            return unitPriceCents * quantity;
        }

        public String getUnitPriceDisplay() {
            return String.format("$%.2f", unitPriceCents / 100.0);
        }

        public String getLineTotalDisplay() {
            return String.format("$%.2f", getLineTotalCents() / 100.0);
        }

        /** Sum of all option upcharges for one unit of this line. */
        public long getUpchargeCents() {
            if (selections == null) return 0L;
            return selections.stream()
                    .mapToLong(SelectedOption::getPriceDeltaCents)
                    .sum();
        }

        /** Base item price (unit price minus upcharges) — useful for "$9.50 + $3.00" breakdowns. */
        public long getBasePriceCents() {
            return unitPriceCents - getUpchargeCents();
        }

        public String getBasePriceDisplay() {
            return String.format("$%.2f", getBasePriceCents() / 100.0);
        }

        public String getUpchargeDisplay() {
            long up = getUpchargeCents();
            String sign = up < 0 ? "-" : "+";
            return sign + String.format("$%.2f", Math.abs(up) / 100.0);
        }

        public boolean hasUpcharge() {
            return getUpchargeCents() != 0L;
        }

        /** Friendly one-liner for the cart, e.g. "Meat: Lamb · Cheese: Cheddar". */
        public String getSelectionsDisplay() {
            if (selections == null || selections.isEmpty()) return "";
            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (SelectedOption s : selections) {
                grouped.computeIfAbsent(s.getGroupLabel(), k -> new ArrayList<>())
                        .add(s.getChoiceLabel());
            }
            StringBuilder sb = new StringBuilder();
            grouped.forEach((g, vs) -> {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(g).append(": ").append(String.join(", ", vs));
            });
            return sb.toString();
        }
    }

    /**
     * One resolved selection on a cart line — captures enough to display
     * in the cart, in the kitchen ticket, and in the receipt.
     *
     * <p>{@link #equals} and {@link #hashCode} are deliberately based on
     * {@code groupId} + {@code choiceId} only (not labels or prices).
     * This is how the merge logic in {@code CartService.addLine}
     * detects "same product, same options" to combine quantities even
     * if pricing or labels drift between adds.
     */
    @Data
    @NoArgsConstructor
    public static class SelectedOption implements Serializable {
        private String groupId;
        private String groupLabel;
        private String choiceId;
        private String choiceLabel;
        private long priceDeltaCents;

        public SelectedOption(String groupId, String groupLabel,
                              String choiceId, String choiceLabel,
                              long priceDeltaCents) {
            this.groupId = groupId;
            this.groupLabel = groupLabel;
            this.choiceId = choiceId;
            this.choiceLabel = choiceLabel;
            this.priceDeltaCents = priceDeltaCents;
        }

        /** "+$0.75" / "-$1.00" / "" when zero. */
        public String getPriceDeltaDisplay() {
            if (priceDeltaCents == 0L) return "";
            String sign = priceDeltaCents < 0 ? "-" : "+";
            return sign + String.format("$%.2f", Math.abs(priceDeltaCents) / 100.0);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SelectedOption that)) return false;
            return Objects.equals(groupId, that.groupId)
                    && Objects.equals(choiceId, that.choiceId);
        }

        @Override public int hashCode() {
            return Objects.hash(groupId, choiceId);
        }
    }
}