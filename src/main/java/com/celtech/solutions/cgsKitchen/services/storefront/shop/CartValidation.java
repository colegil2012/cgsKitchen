package com.celtech.solutions.cgsKitchen.services.storefront.shop;

import com.celtech.solutions.cgsKitchen.models.storefront.shop.Cart;

import java.util.List;

/**
 * Result of {@link CartService#validateAndRepair(Cart)}.
 *
 * <p>If {@link #hadRemovals()} is true, the cart has been modified
 * (stale lines stripped) and persisted. {@link #removedItemNames}
 * contains the display names of the removed items so callers can
 * surface a flash message like:
 *
 * <pre>{@code
 *   if (validation.hadRemovals()) {
 *       redirect.addFlashAttribute("notice",
 *           "Removed unavailable items: " +
 *           String.join(", ", validation.removedItemNames()));
 *   }
 * }</pre>
 *
 * <p>Note: removed-names is best-effort. If the menu item record itself
 * was deleted (not just marked unavailable), we still have the line's
 * cached {@code name} from when it was added, so the customer sees a
 * meaningful message either way.
 */
public record CartValidation(Cart cart, List<String> removedItemNames) {

    public static CartValidation noChange(Cart cart) {
        return new CartValidation(cart, List.of());
    }

    public boolean hadRemovals() {
        return removedItemNames != null && !removedItemNames.isEmpty();
    }
}